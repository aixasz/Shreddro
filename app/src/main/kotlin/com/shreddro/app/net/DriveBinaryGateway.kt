package com.shreddro.app.net

import com.shreddro.app.auth.AppAuthManager
import com.shreddro.core.gateway.BinaryStorageGateway
import com.shreddro.core.model.CloudProvider
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
import java.time.Instant

/**
 * Google Drive raw-image sync (drive.file scope):
 * `My Bank Slips/{BankName}/{epoch}_{file}` — folders resolved/created lazily
 * and cached per bank. Uses multipart upload (metadata + media in one call).
 */
class DriveBinaryGateway(
    private val auth: AppAuthManager,
    private val client: OkHttpClient,
) : BinaryStorageGateway {

    override val provider = CloudProvider.GOOGLE

    private val json = Json { ignoreUnknownKeys = true }
    private val folderCache = mutableMapOf<String, String>()

    override suspend fun upload(bytes: ByteArray, fileName: String, bankKey: String) {
        val token = auth.freshAccessToken(CloudProvider.GOOGLE)
        val rootId = ensureFolder(token, ROOT_FOLDER, parentId = null)
        val bankId = ensureFolder(token, bankKey, parentId = rootId)

        val metadata = """{"name":"${Instant.now().epochSecond}_$fileName","parents":["$bankId"]}"""
        val body = MultipartBody.Builder()
            .setType("multipart/related".toMediaType())
            .addPart(metadata.toRequestBody("application/json; charset=UTF-8".toMediaType()))
            .addPart(bytes.toRequestBody("image/jpeg".toMediaType()))
            .build()

        val request = Request.Builder()
            .url("https://www.googleapis.com/upload/drive/v3/files?uploadType=multipart")
            .header("Authorization", "Bearer $token")
            .post(body)
            .build()

        client.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) {
                throw IOException("Drive upload failed HTTP ${resp.code}: ${resp.body?.string()?.take(300)}")
            }
        }
    }

    private fun ensureFolder(token: String, name: String, parentId: String?): String {
        val cacheKey = "${parentId ?: "root"}/$name"
        folderCache[cacheKey]?.let { return it }

        val parentClause = parentId?.let { " and '$it' in parents" } ?: ""
        val q = "mimeType='application/vnd.google-apps.folder' and name='${name.replace("'", "\\'")}'" +
            " and trashed=false$parentClause"
        val searchUrl = "https://www.googleapis.com/drive/v3/files?q=" +
            java.net.URLEncoder.encode(q, "UTF-8") + "&fields=files(id)"

        val found = client.newCall(
            Request.Builder().url(searchUrl).header("Authorization", "Bearer $token").build(),
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Drive folder search failed HTTP ${resp.code}")
            json.parseToJsonElement(resp.body?.string() ?: "{}").jsonObject["files"]
                ?.jsonArray?.firstOrNull()?.jsonObject?.get("id")?.jsonPrimitive?.content
        }
        if (found != null) return found.also { folderCache[cacheKey] = it }

        val parents = parentId?.let { ""","parents":["$it"]""" } ?: ""
        val createBody =
            """{"name":"$name","mimeType":"application/vnd.google-apps.folder"$parents}"""
        return client.newCall(
            Request.Builder()
                .url("https://www.googleapis.com/drive/v3/files?fields=id")
                .header("Authorization", "Bearer $token")
                .post(createBody.toRequestBody("application/json".toMediaType()))
                .build(),
        ).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException("Drive folder create failed HTTP ${resp.code}")
            json.parseToJsonElement(resp.body?.string() ?: "{}")
                .jsonObject["id"]?.jsonPrimitive?.content
                ?: throw IOException("Drive folder create returned no id")
        }.also { folderCache[cacheKey] = it }
    }

    private companion object {
        const val ROOT_FOLDER = "My Bank Slips"
    }
}
