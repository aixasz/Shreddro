package com.shreddro.core

import com.shreddro.core.gateway.BinaryStorageGateway
import com.shreddro.core.gateway.SpreadsheetGateway
import com.shreddro.core.model.CloudProvider
import com.shreddro.core.model.TransactionSlip
import com.shreddro.core.queue.ArchiveReader
import com.shreddro.core.queue.OpKind
import com.shreddro.core.queue.PendingOp
import com.shreddro.core.queue.SyncQueue
import com.shreddro.core.queue.SyncQueueStore
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SyncQueueTest {

    private val slip = TransactionSlip("KBank", "2026-09-03", 100.0, "A", "B", "R")

    private class MemStore : SyncQueueStore {
        var ops = listOf<PendingOp>()
        override suspend fun load() = ops
        override suspend fun persist(ops: List<PendingOp>) { this.ops = ops }
    }

    private class MemArchive(val files: MutableMap<String, ByteArray> = mutableMapOf()) : ArchiveReader {
        override suspend fun readBytes(archivePath: String) = files[archivePath]
    }

    private class FlakySheet(var failuresLeft: Int) : SpreadsheetGateway {
        override val provider = CloudProvider.GOOGLE
        var appended = 0
        override suspend fun appendRow(slip: TransactionSlip, imageFileName: String) {
            if (failuresLeft-- > 0) error("boom")
            appended++
        }
    }

    private class MemBinary : BinaryStorageGateway {
        override val provider = CloudProvider.GOOGLE
        val uploaded = mutableListOf<String>()
        override suspend fun upload(bytes: ByteArray, fileName: String, bankKey: String) {
            uploaded += fileName
        }
    }

    private fun op(id: String, kind: OpKind = OpKind.SHEET_ROW, archivePath: String? = null) =
        PendingOp(id, CloudProvider.GOOGLE, kind, slip, "media-1", "slip.jpg", archivePath, 0, 0L)

    @Test
    fun `enqueue deduplicates by op id`() = runTest {
        val store = MemStore()
        val queue = SyncQueue(store, MemArchive())
        queue.enqueue(listOf(op("a"), op("b")))
        queue.enqueue(listOf(op("a"), op("c")))
        assertEquals(listOf("a", "b", "c"), store.ops.map { it.id })
    }

    @Test
    fun `drain removes successes and keeps failures with attempt count`() = runTest {
        val store = MemStore()
        val queue = SyncQueue(store, MemArchive())
        val sheet = FlakySheet(failuresLeft = 1)
        queue.enqueue(listOf(op("a"), op("b")))

        val first = queue.drain(mapOf(CloudProvider.GOOGLE to sheet), emptyMap())
        assertEquals(1, first.succeeded)
        assertEquals(1, first.stillPending)
        assertEquals(1, store.ops.single().attempts)

        val second = queue.drain(mapOf(CloudProvider.GOOGLE to sheet), emptyMap())
        assertTrue(second.fullyDrained)
        assertEquals(2, sheet.appended)
        assertEquals(0, store.ops.size)
    }

    @Test
    fun `binary op reads bytes from archive and uploads`() = runTest {
        val store = MemStore()
        val archive = MemArchive(mutableMapOf("/vault/slip.jpg" to byteArrayOf(1, 2)))
        val queue = SyncQueue(store, archive)
        val binary = MemBinary()
        queue.enqueue(listOf(op("bin", OpKind.BINARY, "/vault/slip.jpg")))

        val report = queue.drain(emptyMap(), mapOf(CloudProvider.GOOGLE to binary))

        assertEquals(1, report.succeeded)
        assertEquals(listOf("slip.jpg"), binary.uploaded)
    }

    @Test
    fun `binary op with missing archive file is dropped not retried forever`() = runTest {
        val store = MemStore()
        val queue = SyncQueue(store, MemArchive())
        queue.enqueue(listOf(op("gone", OpKind.BINARY, "/vault/missing.jpg")))

        val report = queue.drain(emptyMap(), mapOf(CloudProvider.GOOGLE to MemBinary()))

        assertEquals(1, report.dropped)
        assertTrue(report.fullyDrained)
    }

    @Test
    fun `op exceeding max attempts is dropped`() = runTest {
        val store = MemStore()
        val queue = SyncQueue(store, MemArchive(), maxAttempts = 2)
        val sheet = FlakySheet(failuresLeft = 99)
        queue.enqueue(listOf(op("poison")))

        queue.drain(mapOf(CloudProvider.GOOGLE to sheet), emptyMap()) // attempt 1 -> kept
        val second = queue.drain(mapOf(CloudProvider.GOOGLE to sheet), emptyMap()) // attempt 2 -> dropped

        assertEquals(1, second.dropped)
        assertEquals(0, store.ops.size)
    }
}
