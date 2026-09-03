package com.shreddro.app.storage

import android.app.Activity
import android.app.RecoverableSecurityException
import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.provider.MediaStore
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import com.shreddro.core.gateway.MediaVault
import com.shreddro.core.gateway.SlipCandidate
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.security.MessageDigest
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * The "Gallery Sweeper" — implements [MediaVault] for Android 10 (API 29) → 15+.
 *
 * Atomic routine per slip:
 *   1. Copy the original's bytes into the app-scoped archive
 *      (`<externalFilesDir>/BankSlips_Archive/{bank}/{yyyy-MM}/`), via a temp
 *      file + fsync + rename so a crash never leaves a half-written archive.
 *   2. Verify integrity (size + SHA-256 against the source candidate).
 *   3. Ensure `.nomedia` exists at the archive root. (App-scoped external dirs
 *      are already excluded from MediaStore scanning; the marker is
 *      defense-in-depth and keeps the guarantee if the archive is ever
 *      relocated to a shared collection.)
 *   4. Only then is the original eligible for purge. Purge is batched:
 *      API 30+ -> one `MediaStore.createDeleteRequest()` system consent dialog
 *      for the whole batch; API 29 -> per-item delete catching
 *      `RecoverableSecurityException` and replaying its IntentSender.
 *
 * The Activity must register an [ActivityResultLauncher] for
 * `StartIntentSenderForResult` and hand it over via [bindLauncher]; the
 * coordinator suspends on the user's consent and resumes via [onPurgeResult].
 */
class StorageCoordinator(
    private val context: Context,
) : MediaVault {

    private val resolver: ContentResolver get() = context.contentResolver

    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var pendingConsent: CompletableDeferred<Boolean>? = null

    /** Call from the Activity after registerForActivityResult. */
    fun bindLauncher(launcher: ActivityResultLauncher<IntentSenderRequest>) {
        this.launcher = launcher
    }

    /** Call from the Activity's result callback with resultCode == RESULT_OK. */
    fun onPurgeResult(granted: Boolean) {
        pendingConsent?.complete(granted)
        pendingConsent = null
    }

    // ── 1-3: archive copy ────────────────────────────────────────────────────

    val archiveRoot: File
        get() = File(context.getExternalFilesDir(null) ?: context.filesDir, ARCHIVE_DIR)

    override suspend fun archive(candidate: SlipCandidate, bankKey: String): String =
        withContext(Dispatchers.IO) {
            val monthDir = LocalDate.now().format(DateTimeFormatter.ofPattern("yyyy-MM"))
            val destDir = File(archiveRoot, "$bankKey/$monthDir").apply { mkdirs() }
            ensureNoMedia()

            val dest = uniqueFile(destDir, candidate.displayName)
            val tmp = File(destDir, "${dest.name}.tmp")
            try {
                FileOutputStream(tmp).use { out ->
                    out.write(candidate.bytes)
                    out.fd.sync() // durable before rename
                }
                if (!tmp.renameTo(dest)) throw IOException("rename failed: ${dest.path}")

                verifyIntegrity(dest, candidate)
                dest.absolutePath
            } catch (e: Exception) {
                tmp.delete()
                dest.delete()
                throw e
            }
        }

    /** Writes an empty `.nomedia` marker at the archive root to block media scanners. */
    fun ensureNoMedia() {
        val root = archiveRoot.apply { mkdirs() }
        val marker = File(root, ".nomedia")
        if (!marker.exists()) {
            marker.createNewFile()
        }
    }

    private fun verifyIntegrity(dest: File, candidate: SlipCandidate) {
        if (dest.length() != candidate.bytes.size.toLong()) {
            throw IOException("Archive size mismatch for ${dest.name}")
        }
        val digest = MessageDigest.getInstance("SHA-256")
        dest.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                digest.update(buf, 0, n)
            }
        }
        val hex = digest.digest().joinToString("") { "%02x".format(it) }
        if (!hex.equals(candidate.sha256, ignoreCase = true)) {
            throw IOException("Archive hash mismatch for ${dest.name}")
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var f = File(dir, name)
        var i = 1
        val base = name.substringBeforeLast('.', name)
        val ext = name.substringAfterLast('.', "").let { if (it.isEmpty()) "" else ".$it" }
        while (f.exists()) f = File(dir, "$base(${i++})$ext")
        return f
    }

    // ── 4: MediaStore purge ──────────────────────────────────────────────────

    /**
     * Requests deletion of the given content URIs from MediaStore.
     * Returns the ids actually purged after user consent.
     */
    override suspend fun requestPurge(mediaIds: List<String>): List<String> {
        if (mediaIds.isEmpty()) return emptyList()
        val uris = mediaIds.map(Uri::parse)

        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            purgeWithDeleteRequest(uris, mediaIds)
        } else {
            purgeApi29(uris, mediaIds)
        }
    }

    /** API 30+: one system dialog covering the entire batch. */
    private suspend fun purgeWithDeleteRequest(uris: List<Uri>, ids: List<String>): List<String> {
        val pendingIntent = MediaStore.createDeleteRequest(resolver, uris)
        val granted = awaitConsent(pendingIntent.intentSender)
        return if (granted) ids else emptyList()
    }

    /**
     * API 29: ContentResolver.delete per item; ownership of another app's media
     * throws RecoverableSecurityException whose IntentSender we replay for consent.
     */
    private suspend fun purgeApi29(uris: List<Uri>, ids: List<String>): List<String> {
        val purged = mutableListOf<String>()
        for ((uri, id) in uris.zip(ids)) {
            if (deleteOneApi29(uri)) purged += id
        }
        return purged
    }

    private suspend fun deleteOneApi29(uri: Uri): Boolean {
        val firstTry = withContext(Dispatchers.IO) {
            try {
                Result.success(resolver.delete(uri, null, null) > 0)
            } catch (e: RecoverableSecurityException) {
                Result.failure(e)
            } catch (e: SecurityException) {
                Result.success(false)
            }
        }
        firstTry.getOrNull()?.let { return it }

        // Media owned by another app: replay the exception's IntentSender for
        // user consent, then retry the delete once.
        val recoverable = firstTry.exceptionOrNull() as RecoverableSecurityException
        val granted = awaitConsent(recoverable.userAction.actionIntent.intentSender)
        if (!granted) return false
        return withContext(Dispatchers.IO) {
            runCatching { resolver.delete(uri, null, null) > 0 }.getOrDefault(false)
        }
    }

    private suspend fun awaitConsent(sender: IntentSender): Boolean {
        val launcher = this.launcher
            ?: throw IllegalStateException("bindLauncher() not called — cannot show consent dialog")
        val deferred = CompletableDeferred<Boolean>()
        pendingConsent = deferred
        launcher.launch(IntentSenderRequest.Builder(sender).build())
        return deferred.await()
    }

    // ── Discovery helpers ────────────────────────────────────────────────────

    /**
     * Enumerates gallery images added after [sinceEpochSeconds], restricted to
     * camera/screenshot buckets — the surfaces where Thai bank slips land.
     */
    suspend fun findCandidates(sinceEpochSeconds: Long): List<GalleryImage> =
        withContext(Dispatchers.IO) {
            val projection = arrayOf(
                MediaStore.Images.Media._ID,
                MediaStore.Images.Media.DISPLAY_NAME,
                MediaStore.Images.Media.DATE_ADDED,
                MediaStore.Images.Media.BUCKET_DISPLAY_NAME,
            )
            val selection = "${MediaStore.Images.Media.DATE_ADDED} > ?"
            val results = mutableListOf<GalleryImage>()
            resolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                projection,
                selection,
                arrayOf(sinceEpochSeconds.toString()),
                "${MediaStore.Images.Media.DATE_ADDED} DESC",
            )?.use { cursor ->
                val idCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
                val nameCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
                val bucketCol = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.BUCKET_DISPLAY_NAME)
                while (cursor.moveToNext()) {
                    val bucket = cursor.getString(bucketCol) ?: ""
                    if (SCAN_BUCKETS.none { bucket.contains(it, ignoreCase = true) }) continue
                    val uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idCol),
                    )
                    results += GalleryImage(uri, cursor.getString(nameCol) ?: "slip.jpg")
                }
            }
            results
        }

    /** Loads bytes + hash to build a core [SlipCandidate]. */
    suspend fun loadCandidate(image: GalleryImage): SlipCandidate = withContext(Dispatchers.IO) {
        val bytes = resolver.openInputStream(image.uri)?.use { it.readBytes() }
            ?: throw IOException("Cannot open ${image.uri}")
        val sha = MessageDigest.getInstance("SHA-256").digest(bytes)
            .joinToString("") { "%02x".format(it) }
        SlipCandidate(image.uri.toString(), image.displayName, sha, bytes)
    }

    data class GalleryImage(val uri: Uri, val displayName: String)

    companion object {
        const val ARCHIVE_DIR = "BankSlips_Archive"
        private val SCAN_BUCKETS = listOf("Camera", "Screenshots", "Screenshot", "Download")
    }
}
