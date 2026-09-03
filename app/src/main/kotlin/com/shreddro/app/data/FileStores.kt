package com.shreddro.app.data

import android.content.Context
import com.shreddro.core.queue.ArchiveReader
import com.shreddro.core.queue.PendingOp
import com.shreddro.core.queue.SyncQueueStore
import com.shreddro.core.registry.ProcessedStatus
import com.shreddro.core.registry.ProcessedStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream

/**
 * File-backed persistence adapters (Phase 1). Both use write-to-temp + fsync +
 * atomic rename so process death never corrupts state. Swap for Room in
 * Phase 2 without touching :core — only these adapters change.
 */

private val json = Json { ignoreUnknownKeys = true }

private fun atomicWrite(target: File, content: String) {
    target.parentFile?.mkdirs()
    val tmp = File(target.parentFile, "${target.name}.tmp")
    FileOutputStream(tmp).use { out ->
        out.write(content.toByteArray(Charsets.UTF_8))
        out.fd.sync()
    }
    if (!tmp.renameTo(target)) {
        tmp.delete()
        throw java.io.IOException("Atomic rename failed for ${target.path}")
    }
}

/** Pending cloud ops: filesDir/state/sync_queue.json */
class FileSyncQueueStore(context: Context) : SyncQueueStore {

    private val file = File(File(context.filesDir, "state"), "sync_queue.json")
    private val serializer = ListSerializer(PendingOp.serializer())

    override suspend fun load(): List<PendingOp> = withContext(Dispatchers.IO) {
        if (!file.exists()) emptyList()
        else runCatching {
            json.decodeFromString(serializer, file.readText(Charsets.UTF_8))
        }.getOrDefault(emptyList())
    }

    override suspend fun persist(ops: List<PendingOp>) = withContext(Dispatchers.IO) {
        atomicWrite(file, json.encodeToString(serializer, ops))
    }
}

/** Processed-image registry: filesDir/state/processed.json (sha256 -> status) */
class FileProcessedStore(context: Context) : ProcessedStore {

    private val file = File(File(context.filesDir, "state"), "processed.json")
    private val serializer = MapSerializer(String.serializer(), ProcessedStatus.serializer())

    override suspend fun load(): Map<String, ProcessedStatus> = withContext(Dispatchers.IO) {
        if (!file.exists()) emptyMap()
        else runCatching {
            json.decodeFromString(serializer, file.readText(Charsets.UTF_8))
        }.getOrDefault(emptyMap())
    }

    override suspend fun persist(entries: Map<String, ProcessedStatus>) =
        withContext(Dispatchers.IO) {
            atomicWrite(file, json.encodeToString(serializer, entries))
        }
}

/** Parked parse failures: filesDir/state/review_queue.json */
class FileReviewStore(context: Context) : com.shreddro.core.review.ReviewStore {

    private val file = File(File(context.filesDir, "state"), "review_queue.json")
    private val serializer = ListSerializer(com.shreddro.core.review.ReviewItem.serializer())

    override suspend fun load(): List<com.shreddro.core.review.ReviewItem> =
        withContext(Dispatchers.IO) {
            if (!file.exists()) emptyList()
            else runCatching {
                json.decodeFromString(serializer, file.readText(Charsets.UTF_8))
            }.getOrDefault(emptyList())
        }

    override suspend fun persist(items: List<com.shreddro.core.review.ReviewItem>) =
        withContext(Dispatchers.IO) {
            atomicWrite(file, json.encodeToString(serializer, items))
        }
}

/** Reads archived slip bytes for deferred BINARY uploads. */
class FileArchiveReader : ArchiveReader {
    override suspend fun readBytes(archivePath: String): ByteArray? =
        withContext(Dispatchers.IO) {
            val f = File(archivePath)
            if (f.isFile) runCatching { f.readBytes() }.getOrNull() else null
        }
}
