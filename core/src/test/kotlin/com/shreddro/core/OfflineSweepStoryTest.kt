package com.shreddro.core

import com.shreddro.core.gateway.BinaryStorageGateway
import com.shreddro.core.gateway.LedgerSink
import com.shreddro.core.gateway.MediaVault
import com.shreddro.core.gateway.SlipCandidate
import com.shreddro.core.gateway.SlipParser
import com.shreddro.core.gateway.SlipValidator
import com.shreddro.core.gateway.SpreadsheetGateway
import com.shreddro.core.gateway.SyncStateProvider
import com.shreddro.core.model.CloudProvider
import com.shreddro.core.model.SyncState
import com.shreddro.core.model.TransactionSlip
import com.shreddro.core.pipeline.SlipPipeline
import com.shreddro.core.pipeline.SlipStage
import com.shreddro.core.queue.ArchiveReader
import com.shreddro.core.queue.OpKind
import com.shreddro.core.queue.PendingOp
import com.shreddro.core.queue.SyncQueue
import com.shreddro.core.queue.SyncQueueStore
import com.shreddro.core.registry.ProcessedRegistry
import com.shreddro.core.registry.ProcessedStatus
import com.shreddro.core.registry.ProcessedStore
import com.shreddro.core.repo.TransactionRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * End-to-end play of the offline-sweep user story at the :core level:
 *
 *   "Google AND Microsoft are linked but there is no internet. The user taps
 *    'Sweep now'. The sweep must still complete locally (ledger row, private
 *    archive, gallery original purged) and the cloud sync must happen later,
 *    automatically, from the ARCHIVE copy once connectivity returns."
 *
 * The fakes model the platform: a FakeVault owns both the "gallery" (originals,
 * removed by purge) and the private archive; the SyncQueue's ArchiveReader
 * reads ONLY the archive — proving the drained binary upload never needs the
 * purged original.
 */
class OfflineSweepStoryTest {

    private val slip = TransactionSlip(
        bankName = "KBank",
        dateTime = "2026-09-03 12:34",
        amount = 1250.50,
        sender = "Somchai",
        receiver = "Somsri",
        referenceId = "REF-42",
    )

    private val originalBytes = byteArrayOf(9, 8, 7, 6)
    private val candidate = SlipCandidate(
        mediaId = "content://media/external/images/77",
        displayName = "slip.jpg",
        sha256 = "sweep-sha",
        bytes = originalBytes,
    )

    // ---------------------------------------------------------------- fakes

    private class MemProcessedStore : ProcessedStore {
        var entries = mapOf<String, ProcessedStatus>()
        override suspend fun load() = entries
        override suspend fun persist(entries: Map<String, ProcessedStatus>) { this.entries = entries }
    }

    private class MemQueueStore : SyncQueueStore {
        var ops = listOf<PendingOp>()
        override suspend fun load() = ops
        override suspend fun persist(ops: List<PendingOp>) { this.ops = ops }
    }

    /**
     * Platform media layer: gallery (originals) + private archive. Purge deletes
     * from the gallery only; the archive copy survives and is what the queue's
     * ArchiveReader serves.
     */
    private class FakeVault : MediaVault, ArchiveReader {
        val gallery = mutableMapOf<String, ByteArray>()
        val archive = mutableMapOf<String, ByteArray>()
        val archiveReads = mutableListOf<String>()

        override suspend fun archive(candidate: SlipCandidate, bankKey: String): String {
            val path = "/vault/$bankKey/${candidate.displayName}"
            archive[path] = candidate.bytes.copyOf()
            return path
        }

        override suspend fun requestPurge(mediaIds: List<String>): List<String> =
            mediaIds.filter { gallery.remove(it) != null }

        override suspend fun readBytes(archivePath: String): ByteArray? {
            archiveReads += archivePath
            return archive[archivePath]
        }
    }

    private class RecordingLedger : LedgerSink {
        val rows = mutableListOf<Pair<TransactionSlip, String>>()
        override suspend fun append(slip: TransactionSlip, sourceMediaId: String) {
            rows += slip to sourceMediaId
        }
    }

    /** Throws while offline so an accidental live call cannot fake a success. */
    private class NetworkedSheet(
        override val provider: CloudProvider,
        val online: () -> Boolean,
    ) : SpreadsheetGateway {
        val rows = mutableListOf<TransactionSlip>()
        override suspend fun appendRow(slip: TransactionSlip) {
            if (!online()) error("no connectivity")
            rows += slip
        }
    }

    private class NetworkedBinaryStore(
        override val provider: CloudProvider,
        val online: () -> Boolean,
    ) : BinaryStorageGateway {
        val uploads = mutableListOf<Triple<ByteArray, String, String>>()
        override suspend fun upload(bytes: ByteArray, fileName: String, bankKey: String) {
            if (!online()) error("no connectivity")
            uploads += Triple(bytes, fileName, bankKey)
        }
    }

    private val passGate = object : SlipValidator {
        override suspend fun looksLikeBankSlip(candidate: SlipCandidate) = true
    }

    private fun stubParser(result: TransactionSlip) = object : SlipParser {
        override suspend fun parse(candidate: SlipCandidate) = result
    }

    // ---------------------------------------------------------------- story

    @Test
    fun `offline sweep completes locally and cloud sync drains later from the archive`() = runTest {
        var online = false

        val vault = FakeVault().apply { gallery[candidate.mediaId] = originalBytes }
        val ledger = RecordingLedger()
        val queueStore = MemQueueStore()
        val syncQueue = SyncQueue(queueStore, archiveReader = vault)

        val sheets = mapOf(
            CloudProvider.GOOGLE to NetworkedSheet(CloudProvider.GOOGLE) { online },
            CloudProvider.MICROSOFT to NetworkedSheet(CloudProvider.MICROSOFT) { online },
        )
        val binaries = mapOf(
            CloudProvider.GOOGLE to NetworkedBinaryStore(CloudProvider.GOOGLE) { online },
            CloudProvider.MICROSOFT to NetworkedBinaryStore(CloudProvider.MICROSOFT) { online },
        )

        val syncStateProvider = object : SyncStateProvider {
            override fun current() = SyncState(
                linkedProviders = setOf(
                    CloudProvider.GOOGLE, CloudProvider.MICROSOFT, CloudProvider.LOCAL_CSV,
                ),
                localModeForced = false,
                online = online,
            )
        }

        val repository = TransactionRepository(
            ledger = ledger,
            spreadsheets = sheets,
            binaryStores = binaries,
            syncStateProvider = syncStateProvider,
            syncQueue = syncQueue,
        )
        val registryStore = MemProcessedStore()
        val pipeline = SlipPipeline(
            validator = passGate,
            parser = stubParser(slip),
            repository = repository,
            vault = vault,
            registry = ProcessedRegistry(registryStore),
        )

        // ---- Phase 1: user taps "Sweep now" with NO internet -------------

        val outcome = pipeline.process(candidate)

        // (a) local ledger row written — the durability anchor.
        assertEquals(1, ledger.rows.size)
        assertEquals(slip, ledger.rows.single().first)
        assertEquals(candidate.mediaId, ledger.rows.single().second)

        // (b) private archive copy created before recording.
        val archivePath = outcome.archivePath
        assertNotNull(archivePath)
        assertContentEquals(originalBytes, vault.archive[archivePath])

        // (c) outcome stage allows purge despite zero cloud successes.
        assertEquals(SlipStage.ARCHIVED, outcome.stage)
        assertEquals(ProcessedStatus.DONE, registryStore.entries[candidate.sha256])

        // (d) purge of the gallery original succeeds while still offline.
        assertEquals(listOf(candidate.mediaId), pipeline.purge(listOf(outcome)))
        assertNull(vault.gallery[candidate.mediaId]) // original gone

        // Nothing reached the cloud yet.
        assertTrue(sheets.values.all { it.rows.isEmpty() })
        assertTrue(binaries.values.all { it.uploads.isEmpty() })

        // (e) queue durably holds SHEET_ROW + BINARY for BOTH providers,
        //     BINARY ops pointing at the archive path.
        assertEquals(4, syncQueue.pendingCount())
        val opKeys = queueStore.ops.map { it.provider to it.kind }.toSet()
        assertEquals(
            setOf(
                CloudProvider.GOOGLE to OpKind.SHEET_ROW,
                CloudProvider.GOOGLE to OpKind.BINARY,
                CloudProvider.MICROSOFT to OpKind.SHEET_ROW,
                CloudProvider.MICROSOFT to OpKind.BINARY,
            ),
            opKeys,
        )
        queueStore.ops.filter { it.kind == OpKind.BINARY }.forEach {
            assertEquals(archivePath, it.archivePath)
        }
        queueStore.ops.filter { it.kind == OpKind.SHEET_ROW }.forEach {
            assertNull(it.archivePath)
        }

        // ---- Phase 2: connectivity returns; background drain runs --------

        online = true
        val report = syncQueue.drain(sheets, binaries)

        assertTrue(report.fullyDrained)
        assertEquals(4, report.succeeded)
        assertEquals(0, report.dropped)
        assertEquals(0, syncQueue.pendingCount())

        // Both providers received the spreadsheet row...
        assertEquals(listOf(slip), sheets.getValue(CloudProvider.GOOGLE).rows)
        assertEquals(listOf(slip), sheets.getValue(CloudProvider.MICROSOFT).rows)

        // ...and the binary, served from the ARCHIVE copy (original purged).
        assertEquals(listOf(archivePath, archivePath), vault.archiveReads)
        for (provider in listOf(CloudProvider.GOOGLE, CloudProvider.MICROSOFT)) {
            val upload = binaries.getValue(provider).uploads.single()
            assertContentEquals(originalBytes, upload.first)
            assertEquals(candidate.displayName, upload.second)
            assertEquals(slip.bankKey, upload.third)
        }
    }

    @Test
    fun `drain while still offline keeps every op for the next attempt`() = runTest {
        var online = false

        val vault = FakeVault().apply { gallery[candidate.mediaId] = originalBytes }
        val queueStore = MemQueueStore()
        val syncQueue = SyncQueue(queueStore, archiveReader = vault)
        val sheets = mapOf(
            CloudProvider.GOOGLE to NetworkedSheet(CloudProvider.GOOGLE) { online },
            CloudProvider.MICROSOFT to NetworkedSheet(CloudProvider.MICROSOFT) { online },
        )
        val binaries = mapOf(
            CloudProvider.GOOGLE to NetworkedBinaryStore(CloudProvider.GOOGLE) { online },
            CloudProvider.MICROSOFT to NetworkedBinaryStore(CloudProvider.MICROSOFT) { online },
        )
        val repository = TransactionRepository(
            ledger = RecordingLedger(),
            spreadsheets = sheets,
            binaryStores = binaries,
            syncStateProvider = object : SyncStateProvider {
                override fun current() = SyncState(
                    linkedProviders = setOf(CloudProvider.GOOGLE, CloudProvider.MICROSOFT),
                    online = online,
                )
            },
            syncQueue = syncQueue,
        )
        val pipeline = SlipPipeline(
            validator = passGate,
            parser = stubParser(slip),
            repository = repository,
            vault = vault,
            registry = ProcessedRegistry(MemProcessedStore()),
        )

        val outcome = pipeline.process(candidate)
        assertEquals(SlipStage.ARCHIVED, outcome.stage)
        pipeline.purge(listOf(outcome))

        // A premature drain (worker fires but the network is actually dead).
        val offlineReport = syncQueue.drain(sheets, binaries)
        assertEquals(0, offlineReport.succeeded)
        assertEquals(4, offlineReport.stillPending)
        assertTrue(queueStore.ops.all { it.attempts == 1 })

        // Later, online: everything still drains from the archive.
        online = true
        val report = syncQueue.drain(sheets, binaries)
        assertTrue(report.fullyDrained)
        assertEquals(4, report.succeeded)
        assertTrue(binaries.values.all { it.uploads.size == 1 })
    }
}
