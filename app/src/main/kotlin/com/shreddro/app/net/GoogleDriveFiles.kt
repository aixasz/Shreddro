package com.shreddro.app.net

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder

/**
 * Thin Drive v3 helper shared by the Google image and spreadsheet gateways so
 * both resolve the SAME `Shreddro/<bank>/` folders (one in-memory id cache).
 *
 * Every lookup is "find by name under parent, else create" — nothing is ever
 * duplicated on retries, and a folder/file the user already has is reused.
 * Works within the `drive.file` scope: the app only ever sees what it created.
 */
class GoogleDriveFiles(private val client: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }
    private val folderCache = mutableMapOf<String, String>()

    /** `Shreddro/<bankKey>` folder id, creating either level only when absent. */
    fun ensureBankFolder(token: String, bankKey: String): String {
        val root = ensureFolder(token, ROOT_FOLDER, parentId = null)
        return ensureFolder(token, bankKey, parentId = root)
    }

    fun rootFolderUrl(token: String): String =
        "https://drive.google.com/drive/folders/" + ensureFolder(token, ROOT_FOLDER, parentId = null)

    fun ensureFolder(token: String, name: String, parentId: String?): String {
        val cacheKey = "${parentId ?: "root"}/$name"
        folderCache[cacheKey]?.let { return it }
        val found = findByName(token, name, parentId, FOLDER_MIME)
        if (found != null) return found.also { folderCache[cacheKey] = it }
        return createMetadata(token, name, parentId, FOLDER_MIME).also { folderCache[cacheKey] = it }
    }

    /** First non-trashed child of [parentId] named [name] (optionally of [mimeType]). */
    fun findByName(token: String, name: String, parentId: String?, mimeType: String? = null): String? {
        val q = buildString {
            append("name='").append(name.replace("\\", "\\\\").replace("'", "\\'")).append("'")
            append(" and trashed=false")
            if (parentId != null) append(" and '").append(parentId).append("' in parents")
            if (mimeType != null) append(" and mimeType='").append(mimeType).append("'")
        }
        val url = "https://www.googleapis.com/drive/v3/files?q=" +
            URLEncoder.encode(q, "UTF-8") + "&fields=files(id)&pageSize=1"
        return client.newCall(
            Request.Builder().url(url).header("Authorization", "Bearer $token").build(),
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Drive search failed HTTP ${resp.code}")
            json.parseToJsonElement(resp.body?.string() ?: "{}").jsonObject["files"]
                ?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
        }
    }

    /** Creates a metadata-only file (folder or native Google Sheet) and returns its id. */
    fun createMetadata(token: String, name: String, parentId: String?, mimeType: String): String {
        val parents = parentId?.let { ""","parents":["$it"]""" } ?: ""
        val body = """{"name":${jsonString(name)},"mimeType":"$mimeType"$parents}"""
        return client.newCall(
            Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files?fields=id")
                .header("Authorization", "Bearer $token")
                .post(body.toRequestBody(JSON_MEDIA))
                .build(),
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Drive create failed HTTP ${resp.code}: ${resp.body?.string()?.take(200)}")
            json.parseToJsonElement(resp.body?.string() ?: "{}")
                .jsonObject["id"]?.jsonPrimitive?.content
                ?: throw IOException("Drive create returned no id")
        }
    }

    /** Multipart upload (metadata + media in one call) into [parentId]. */
    fun uploadBytes(token: String, name: String, parentId: String, bytes: ByteArray, mimeType: String) {
        val metadata = """{"name":${jsonString(name)},"parents":["$parentId"]}"""
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(bytes.toRequestBody(mimeType.toMediaType()))
            .build()
        client.newCall(
            Request.Builder()
                .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
                .header("Authorization", "Bearer $token")
                .post(body)
                .build(),
        ).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Drive upload failed HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
            }
        }
    }

    private fun jsonString(s: String): String =
        "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""

    companion object {
        /** Everything Shreddro writes lives under this one folder in My Drive. */
        const val ROOT_FOLDER = "Shreddro"
        const val FOLDER_MIME = "application/vnd.google-apps.folder"
        const val SHEET_MIME = "application/vnd.google-apps.spreadsheet"
        val JSON_MEDIA = "application/json".toMediaType()
    }
}
