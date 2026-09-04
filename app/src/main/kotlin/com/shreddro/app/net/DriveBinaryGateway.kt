package com.shreddro.app.net

import android.util.Log
import com.shreddro.app.auth.AppAuthManager
import com.shreddro.core.gateway.BinaryStorageGateway
import com.shreddro.core.model.CloudProvider

/**
 * Google Drive slip-image sync (drive.file scope):
 * `Shreddro/<bank>/<fileName>` — folders are looked up by name and created
 * only when missing; a file whose name already exists in the bank folder is
 * left alone (queued retries and rescans never produce duplicates).
 */
class DriveBinaryGateway(
    private val auth: AppAuthManager,
    private val files: GoogleDriveFiles,
    /** Called with the root `Shreddro` folder URL so the UI can deep-link to Drive. */
    private val onFolderUrl: (String) -> Unit = {},
) : BinaryStorageGateway {

    override val provider = CloudProvider.GOOGLE
    private var urlReported = false

    override suspend fun upload(bytes: ByteArray, fileName: String, bankKey: String) {
        val token = auth.freshAccessToken(CloudProvider.GOOGLE)
        val bankFolder = files.ensureBankFolder(token, bankKey)
        if (!urlReported) {
            onFolderUrl(files.rootFolderUrl(token))
            urlReported = true
        }

        if (files.findByName(token, fileName, bankFolder) != null) {
            Log.d(TAG, "Drive: $bankKey/$fileName already exists — skipped")
            return
        }
        files.uploadBytes(token, fileName, bankFolder, bytes, mimeTypeFor(fileName))
        Log.d(TAG, "Drive: uploaded $bankKey/$fileName (${bytes.size} B)")
    }

    private companion object {
        const val TAG = "Shreddro.Upload"

        fun mimeTypeFor(fileName: String): String =
            when (fileName.substringAfterLast('.', "").lowercase()) {
                "png" -> "image/png"
                "webp" -> "image/webp"
                "heic", "heif" -> "image/heic"
                else -> "image/jpeg"
            }
    }
}
