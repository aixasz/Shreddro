package com.shreddro.app.data

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * Archived-but-not-yet-purged originals. Backs the Home hero's "N slips ready
 * to sweep" count and lets a later session (or a background scan's archive
 * work) hand its purge batch to the next foreground moment — the consent
 * dialog needs a visible Activity.
 */
@Serializable
data class PendingPurge(val mediaId: String, val fileName: String)

class PendingPurgeStore(context: Context) {

    private val file = File(File(context.filesDir, "state"), "pending_purge.json")
    private val serializer = ListSerializer(PendingPurge.serializer())
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()

    suspend fun list(): List<PendingPurge> = mutex.withLock { load() }

    suspend fun add(entries: List<PendingPurge>) {
        if (entries.isEmpty()) return
        mutex.withLock {
            val current = load()
            val known = current.mapTo(mutableSetOf()) { it.mediaId }
            persist(current + entries.filter { it.mediaId !in known })
        }
    }

    suspend fun remove(mediaIds: Collection<String>) {
        if (mediaIds.isEmpty()) return
        mutex.withLock {
            persist(load().filterNot { it.mediaId in mediaIds })
        }
    }

    private suspend fun load(): List<PendingPurge> = withContext(Dispatchers.IO) {
        if (!file.exists()) emptyList()
        else runCatching {
            json.decodeFromString(serializer, file.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    private suspend fun persist(entries: List<PendingPurge>) = withContext(Dispatchers.IO) {
        file.parentFile?.mkdirs()
        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(json.encodeToString(serializer, entries).toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) tmp.delete()
    }
}
