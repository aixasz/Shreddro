package com.shreddro.core

import com.shreddro.core.gateway.BinaryStorageGateway
import com.shreddro.core.gateway.LedgerSink
import com.shreddro.core.gateway.SlipCandidate
import com.shreddro.core.gateway.SpreadsheetGateway
import com.shreddro.core.gateway.SyncStateProvider
import com.shreddro.core.model.CloudProvider
import com.shreddro.core.model.SyncOutcome
import com.shreddro.core.model.SyncState
import com.shreddro.core.model.TransactionSlip
import com.shreddro.core.repo.TransactionRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class TransactionRepositoryTest {

    private val slip = TransactionSlip("SCB", "2026-09-03 10:00", 500.0, "A", "B", "R1")
    private val candidate = SlipCandidate("content://media/1", "slip.jpg", "abc123", byteArrayOf(1))

    private class FakeLedger(var fail: Boolean = false) : LedgerSink {
        val rows = mutableListOf<Pair<TransactionSlip, String>>()
        override suspend fun append(slip: TransactionSlip, sourceMediaId: String) {
            if (fail) error("disk full")
            rows += slip to sourceMediaId
        }
    }

    private class FakeSheet(override val provider: CloudProvider, var fail: Boolean = false) : SpreadsheetGateway {
        var appended = 0
        override suspend fun appendRow(slip: TransactionSlip) {
            if (fail) error("http 500")
            appended++
        }
    }

    private class FakeBinary(override val provider: CloudProvider) : BinaryStorageGateway {
        var uploads = 0
        override suspend fun upload(bytes: ByteArray, fileName: String, bankKey: String) { uploads++ }
    }

    private fun state(vararg linked: CloudProvider, localMode: Boolean = false, online: Boolean = true) =
        object : SyncStateProvider {
            override fun current() = SyncState(linked.toSet(), localMode, online)
        }

    @Test
    fun `logs locally and fans out to both linked providers`() = runTest {
        val ledger = FakeLedger()
        val gSheet = FakeSheet(CloudProvider.GOOGLE)
        val mSheet = FakeSheet(CloudProvider.MICROSOFT)
        val gBin = FakeBinary(CloudProvider.GOOGLE)
        val repo = TransactionRepository(
            ledger,
            mapOf(CloudProvider.GOOGLE to gSheet, CloudProvider.MICROSOFT to mSheet),
            mapOf(CloudProvider.GOOGLE to gBin),
            state(CloudProvider.GOOGLE, CloudProvider.MICROSOFT),
        )

        val result = repo.record(slip, candidate)

        assertEquals(1, ledger.rows.size)
        assertEquals(1, gSheet.appended)
        assertEquals(1, mSheet.appended)
        assertEquals(1, gBin.uploads)
        assertTrue(result.safeToPurgeOriginal)
        assertTrue(result.cloud.all { it is SyncOutcome.Success })
    }

    @Test
    fun `offline defers cloud targets but still logs locally`() = runTest {
        val ledger = FakeLedger()
        val gSheet = FakeSheet(CloudProvider.GOOGLE)
        val repo = TransactionRepository(
            ledger,
            mapOf(CloudProvider.GOOGLE to gSheet),
            emptyMap(),
            state(CloudProvider.GOOGLE, online = false),
        )

        val result = repo.record(slip, candidate)

        assertEquals(1, ledger.rows.size)
        assertEquals(0, gSheet.appended)
        assertTrue(result.safeToPurgeOriginal)
        assertEquals(listOf(CloudProvider.GOOGLE), result.pendingRetry)
        assertTrue(result.cloud.single() is SyncOutcome.Deferred)
    }

    @Test
    fun `local mode bypasses cloud even when online`() = runTest {
        val gSheet = FakeSheet(CloudProvider.GOOGLE)
        val repo = TransactionRepository(
            FakeLedger(),
            mapOf(CloudProvider.GOOGLE to gSheet),
            emptyMap(),
            state(CloudProvider.GOOGLE, localMode = true),
        )
        val result = repo.record(slip, candidate)
        assertEquals(0, gSheet.appended)
        assertTrue(result.cloud.single() is SyncOutcome.Deferred)
    }

    @Test
    fun `one provider failing does not affect the other`() = runTest {
        val gSheet = FakeSheet(CloudProvider.GOOGLE, fail = true)
        val mSheet = FakeSheet(CloudProvider.MICROSOFT)
        val repo = TransactionRepository(
            FakeLedger(),
            mapOf(CloudProvider.GOOGLE to gSheet, CloudProvider.MICROSOFT to mSheet),
            emptyMap(),
            state(CloudProvider.GOOGLE, CloudProvider.MICROSOFT),
        )

        val result = repo.record(slip, candidate)

        assertEquals(1, mSheet.appended)
        assertEquals(listOf(CloudProvider.GOOGLE), result.pendingRetry)
        assertTrue(result.safeToPurgeOriginal)
    }

    private class MemQueueStore : com.shreddro.core.queue.SyncQueueStore {
        var ops = listOf<com.shreddro.core.queue.PendingOp>()
        override suspend fun load() = ops
        override suspend fun persist(ops: List<com.shreddro.core.queue.PendingOp>) { this.ops = ops }
    }

    private fun queueOn(store: MemQueueStore) = com.shreddro.core.queue.SyncQueue(
        store,
        object : com.shreddro.core.queue.ArchiveReader {
            override suspend fun readBytes(archivePath: String): ByteArray? = null
        },
    )

    @Test
    fun `offline enqueues sheet and binary ops for later drain`() = runTest {
        val store = MemQueueStore()
        val repo = TransactionRepository(
            FakeLedger(),
            mapOf(CloudProvider.GOOGLE to FakeSheet(CloudProvider.GOOGLE)),
            mapOf(CloudProvider.GOOGLE to FakeBinary(CloudProvider.GOOGLE)),
            state(CloudProvider.GOOGLE, online = false),
            syncQueue = queueOn(store),
        )

        repo.record(slip, candidate, archivePath = "/vault/slip.jpg")

        assertEquals(
            listOf(
                com.shreddro.core.queue.OpKind.SHEET_ROW,
                com.shreddro.core.queue.OpKind.BINARY,
            ),
            store.ops.map { it.kind },
        )
        assertEquals("/vault/slip.jpg", store.ops.last().archivePath)
    }

    @Test
    fun `forced local mode does not enqueue cloud work`() = runTest {
        val store = MemQueueStore()
        val repo = TransactionRepository(
            FakeLedger(),
            mapOf(CloudProvider.GOOGLE to FakeSheet(CloudProvider.GOOGLE)),
            emptyMap(),
            state(CloudProvider.GOOGLE, localMode = true),
            syncQueue = queueOn(store),
        )
        repo.record(slip, candidate, archivePath = "/vault/slip.jpg")
        assertEquals(0, store.ops.size)
    }

    @Test
    fun `online sheet failure enqueues just the failed op`() = runTest {
        val store = MemQueueStore()
        val binary = FakeBinary(CloudProvider.GOOGLE)
        val repo = TransactionRepository(
            FakeLedger(),
            mapOf(CloudProvider.GOOGLE to FakeSheet(CloudProvider.GOOGLE, fail = true)),
            mapOf(CloudProvider.GOOGLE to binary),
            state(CloudProvider.GOOGLE),
            syncQueue = queueOn(store),
        )

        repo.record(slip, candidate, archivePath = "/vault/slip.jpg")

        assertEquals(1, binary.uploads) // binary still succeeded
        assertEquals(
            listOf(com.shreddro.core.queue.OpKind.SHEET_ROW),
            store.ops.map { it.kind },
        )
    }

    @Test
    fun `ledger failure blocks purge eligibility`() = runTest {
        val repo = TransactionRepository(
            FakeLedger(fail = true), emptyMap(), emptyMap(), state(),
        )
        val result = repo.record(slip, candidate)
        assertFalse(result.safeToPurgeOriginal)
    }
}
