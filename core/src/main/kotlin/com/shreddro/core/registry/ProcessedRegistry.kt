package com.shreddro.core.registry

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

@Serializable
enum class ProcessedStatus {
    /** Row logged (at least locally) — reprocessing would duplicate ledger rows. */
    DONE,

    /** Failed the on-device gate — not a bank slip; never send to the LLM again. */
    SKIPPED,

    /** LLM parse failed twice — parked for manual review; retried only explicitly. */
    NEEDS_REVIEW,
}

/** Durable storage port (platform supplies the adapter). */
interface ProcessedStore {
    suspend fun load(): Map<String, ProcessedStatus>
    suspend fun persist(entries: Map<String, ProcessedStatus>)
}

/**
 * Dedup registry keyed by image SHA-256. Prevents rescans from re-invoking the
 * Vision LLM (cost) or appending duplicate rows (correctness) for images that
 * were already handled — including originals that failed to purge and images
 * arriving again under a different MediaStore id.
 */
class ProcessedRegistry(private val store: ProcessedStore) {

    private val mutex = Mutex()
    private var cache: MutableMap<String, ProcessedStatus>? = null

    suspend fun statusOf(sha256: String): ProcessedStatus? =
        mutex.withLock { loadCache()[sha256] }

    suspend fun record(sha256: String, status: ProcessedStatus) {
        mutex.withLock {
            val entries = loadCache()
            entries[sha256] = status
            store.persist(entries)
        }
    }

    /** Clears a NEEDS_REVIEW mark so an explicit user retry can reprocess. */
    suspend fun clearForRetry(sha256: String) {
        mutex.withLock {
            val entries = loadCache()
            if (entries[sha256] == ProcessedStatus.NEEDS_REVIEW) {
                entries.remove(sha256)
                store.persist(entries)
            }
        }
    }

    private suspend fun loadCache(): MutableMap<String, ProcessedStatus> =
        cache ?: store.load().toMutableMap().also { cache = it }
}
