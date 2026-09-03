package com.shreddro.core.queue

import com.shreddro.core.gateway.BinaryStorageGateway
import com.shreddro.core.gateway.SpreadsheetGateway
import com.shreddro.core.model.CloudProvider
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Durable storage port for the queue (platform supplies the adapter). */
interface SyncQueueStore {
    suspend fun load(): List<PendingOp>
    suspend fun persist(ops: List<PendingOp>)
}

/** Reads image bytes back out of the private archive for deferred uploads. */
interface ArchiveReader {
    /** Returns null when the archived file no longer exists. */
    suspend fun readBytes(archivePath: String): ByteArray?
}

data class DrainReport(
    val succeeded: Int,
    val stillPending: Int,
    val dropped: Int,
) {
    val fullyDrained: Boolean get() = stillPending == 0
}

/**
 * Persistent retry queue for deferred/failed cloud syncs.
 *
 * - [enqueue] is called by the repository whenever a cloud target is offline
 *   or errors; the op is persisted immediately (survives process death).
 * - [drain] is called by a background worker under network constraints. Each
 *   op is retried against its gateway; successes are removed, failures keep
 *   the op with an incremented attempt count, and ops exceeding [maxAttempts]
 *   are dropped so a permanently broken record cannot wedge the queue.
 * - Binary ops re-read bytes from the private archive: the gallery original
 *   may already be purged by drain time (by design). A missing archive file
 *   drops the op.
 */
class SyncQueue(
    private val store: SyncQueueStore,
    private val archiveReader: ArchiveReader,
    private val maxAttempts: Int = 10,
) {
    private val mutex = Mutex()

    suspend fun enqueue(ops: List<PendingOp>) {
        if (ops.isEmpty()) return
        mutex.withLock {
            // Op ids are deterministic (sha:provider:kind) — a re-recorded slip
            // must not double-queue the same work.
            val existing = store.load()
            val known = existing.mapTo(mutableSetOf()) { it.id }
            val fresh = ops.filter { it.id !in known }
            if (fresh.isNotEmpty()) store.persist(existing + fresh)
        }
    }

    suspend fun pendingCount(): Int = mutex.withLock { store.load().size }

    suspend fun drain(
        spreadsheets: Map<CloudProvider, SpreadsheetGateway>,
        binaryStores: Map<CloudProvider, BinaryStorageGateway>,
    ): DrainReport = mutex.withLock {
        val ops = store.load()
        var succeeded = 0
        var dropped = 0
        val remaining = mutableListOf<PendingOp>()

        for (op in ops) {
            val result = runCatching { execute(op, spreadsheets, binaryStores) }
            when {
                result.isSuccess && result.getOrThrow() -> succeeded++
                result.isSuccess -> dropped++ // unexecutable (no gateway / archive gone)
                op.attempts + 1 >= maxAttempts -> dropped++
                else -> remaining += op.copy(attempts = op.attempts + 1)
            }
        }

        store.persist(remaining)
        DrainReport(succeeded, remaining.size, dropped)
    }

    /**
     * @return true when synced; false when the op can never succeed and should
     *         be dropped silently; throws to keep the op for a later retry.
     */
    private suspend fun execute(
        op: PendingOp,
        spreadsheets: Map<CloudProvider, SpreadsheetGateway>,
        binaryStores: Map<CloudProvider, BinaryStorageGateway>,
    ): Boolean = when (op.kind) {
        OpKind.SHEET_ROW -> {
            val gateway = spreadsheets[op.provider] ?: return false
            gateway.appendRow(op.slip)
            true
        }
        OpKind.BINARY -> {
            val gateway = binaryStores[op.provider] ?: return false
            val path = op.archivePath ?: return false
            val bytes = archiveReader.readBytes(path) ?: return false
            gateway.upload(bytes, op.fileName, op.slip.bankKey)
            true
        }
    }
}
