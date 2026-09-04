package com.shreddro.core

import com.shreddro.core.gateway.LedgerSink
import com.shreddro.core.gateway.MediaVault
import com.shreddro.core.gateway.SlipCandidate
import com.shreddro.core.gateway.SlipParseException
import com.shreddro.core.gateway.SlipParser
import com.shreddro.core.gateway.SlipValidator
import com.shreddro.core.gateway.SyncStateProvider
import com.shreddro.core.model.SyncState
import com.shreddro.core.model.TransactionSlip
import com.shreddro.core.pipeline.SlipPipeline
import com.shreddro.core.pipeline.SlipStage
import com.shreddro.core.registry.ProcessedRegistry
import com.shreddro.core.registry.ProcessedStatus
import com.shreddro.core.registry.ProcessedStore
import com.shreddro.core.repo.TransactionRepository
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals

class SlipPipelineTest {

    private val slip = TransactionSlip("SCB", "2026-09-03 10:00", 500.0, "A", "B", "R1")
    private fun candidate(sha: String) = SlipCandidate("content://media/1", "s.jpg", sha, byteArrayOf(1))

    private class MemProcessedStore : ProcessedStore {
        var entries = mapOf<String, ProcessedStatus>()
        override suspend fun load() = entries
        override suspend fun persist(entries: Map<String, ProcessedStatus>) { this.entries = entries }
    }

    private class FakeVault(var failArchive: Boolean = false) : MediaVault {
        val archived = mutableListOf<String>()
        override suspend fun archive(candidate: SlipCandidate, bankKey: String): String {
            if (failArchive) error("disk error")
            return "/vault/${candidate.displayName}".also { archived += it }
        }
        override suspend fun requestPurge(mediaIds: List<String>) = mediaIds
    }

    private class CountingLedger : LedgerSink {
        var appends = 0
        override suspend fun append(slip: TransactionSlip, sourceMediaId: String, imageFileName: String) { appends++ }
    }

    private class CountingParser(val result: TransactionSlip) : SlipParser {
        var calls = 0
        override suspend fun parse(candidate: SlipCandidate): TransactionSlip {
            calls++
            return result
        }
    }

    private val passGate = object : SlipValidator {
        override suspend fun looksLikeBankSlip(candidate: SlipCandidate) = true
    }

    private fun pipeline(
        parser: SlipParser,
        ledger: LedgerSink,
        vault: MediaVault,
        store: ProcessedStore,
    ): SlipPipeline {
        val repo = TransactionRepository(
            ledger, emptyMap(), emptyMap(),
            object : SyncStateProvider { override fun current() = SyncState(emptySet()) },
        )
        return SlipPipeline(passGate, parser, repo, vault, ProcessedRegistry(store))
    }

    @Test
    fun `happy path archives and marks done`() = runTest {
        val store = MemProcessedStore()
        val ledger = CountingLedger()
        val p = pipeline(CountingParser(slip), ledger, FakeVault(), store)

        val outcome = p.process(candidate("sha1"))

        assertEquals(SlipStage.ARCHIVED, outcome.stage)
        assertEquals("/vault/s.jpg", outcome.archivePath)
        assertEquals(1, ledger.appends)
        assertEquals(ProcessedStatus.DONE, store.entries["sha1"])
    }

    @Test
    fun `second pass with same hash is deduplicated — no llm call, no duplicate row`() = runTest {
        val store = MemProcessedStore()
        val ledger = CountingLedger()
        val parser = CountingParser(slip)
        val p = pipeline(parser, ledger, FakeVault(), store)

        p.process(candidate("sha1"))
        val second = p.process(candidate("sha1"))

        assertEquals(SlipStage.ALREADY_PROCESSED, second.stage)
        assertEquals(1, parser.calls)
        assertEquals(1, ledger.appends)
    }

    @Test
    fun `gate failure marks skipped so rescans never re-gate`() = runTest {
        val store = MemProcessedStore()
        val failGate = object : SlipValidator {
            override suspend fun looksLikeBankSlip(candidate: SlipCandidate) = false
        }
        val repo = TransactionRepository(
            CountingLedger(), emptyMap(), emptyMap(),
            object : SyncStateProvider { override fun current() = SyncState(emptySet()) },
        )
        val p = SlipPipeline(failGate, CountingParser(slip), repo, FakeVault(), ProcessedRegistry(store))

        assertEquals(SlipStage.SKIPPED, p.process(candidate("shaX")).stage)
        assertEquals(ProcessedStatus.SKIPPED, store.entries["shaX"])
        assertEquals(SlipStage.ALREADY_PROCESSED, p.process(candidate("shaX")).stage)
    }

    @Test
    fun `parse failure parks as needs review and is not retried implicitly`() = runTest {
        val store = MemProcessedStore()
        val failingParser = object : SlipParser {
            var calls = 0
            override suspend fun parse(candidate: SlipCandidate): TransactionSlip {
                calls++
                throw SlipParseException("garbage")
            }
        }
        val p = pipeline(failingParser, CountingLedger(), FakeVault(), store)

        assertEquals(SlipStage.NEEDS_REVIEW, p.process(candidate("shaR")).stage)
        assertEquals(ProcessedStatus.NEEDS_REVIEW, store.entries["shaR"])
        assertEquals(2, failingParser.calls) // 1 + parseRetries(1)
        assertEquals(SlipStage.ALREADY_PROCESSED, p.process(candidate("shaR")).stage)
    }

    private class MemReviewStore : com.shreddro.core.review.ReviewStore {
        var items = listOf<com.shreddro.core.review.ReviewItem>()
        override suspend fun load() = items
        override suspend fun persist(items: List<com.shreddro.core.review.ReviewItem>) {
            this.items = items
        }
    }

    @Test
    fun `parse failure enqueues a review item and retry can succeed later`() = runTest {
        val store = MemProcessedStore()
        val reviewStore = MemReviewStore()
        val ledger = CountingLedger()
        val flakyParser = object : SlipParser {
            var failuresLeft = 2 // fails initial attempt + its retry, then works
            override suspend fun parse(candidate: SlipCandidate): TransactionSlip {
                if (failuresLeft-- > 0) throw SlipParseException("garbage")
                return slip
            }
        }
        val repo = TransactionRepository(
            ledger, emptyMap(), emptyMap(),
            object : SyncStateProvider { override fun current() = SyncState(emptySet()) },
        )
        val reviewQueue = com.shreddro.core.review.ReviewQueue(reviewStore)
        val p = SlipPipeline(
            passGate, flakyParser, repo, FakeVault(),
            ProcessedRegistry(store), reviewQueue,
        )
        val c = candidate("shaRetry")

        assertEquals(SlipStage.NEEDS_REVIEW, p.process(c).stage)
        assertEquals(1, reviewQueue.count())

        val retried = p.retry(c)

        assertEquals(SlipStage.ARCHIVED, retried.stage)
        assertEquals(0, reviewQueue.count())
        assertEquals(ProcessedStatus.DONE, store.entries["shaRetry"])
        assertEquals(1, ledger.appends)
    }

    @Test
    fun `manual resolution skips the llm and completes the tail`() = runTest {
        val store = MemProcessedStore()
        val reviewStore = MemReviewStore()
        val ledger = CountingLedger()
        val alwaysFailing = object : SlipParser {
            override suspend fun parse(candidate: SlipCandidate): TransactionSlip =
                throw SlipParseException("unreadable")
        }
        val repo = TransactionRepository(
            ledger, emptyMap(), emptyMap(),
            object : SyncStateProvider { override fun current() = SyncState(emptySet()) },
        )
        val reviewQueue = com.shreddro.core.review.ReviewQueue(reviewStore)
        val vault = FakeVault()
        val p = SlipPipeline(
            passGate, alwaysFailing, repo, vault,
            ProcessedRegistry(store), reviewQueue,
        )
        val c = candidate("shaManual")

        p.process(c)
        val resolved = p.resolveManually(c, slip)

        assertEquals(SlipStage.ARCHIVED, resolved.stage)
        assertEquals(0, reviewQueue.count())
        assertEquals(ProcessedStatus.DONE, store.entries["shaManual"])
        assertEquals(1, ledger.appends)
        assertEquals(1, vault.archived.size)
    }

    @Test
    fun `archive failure still logs locally but original is never purge-eligible`() = runTest {
        val store = MemProcessedStore()
        val ledger = CountingLedger()
        val p = pipeline(CountingParser(slip), ledger, FakeVault(failArchive = true), store)

        val outcome = p.process(candidate("shaA"))

        assertEquals(SlipStage.LOGGED_LOCAL, outcome.stage)
        assertEquals(1, ledger.appends)
        assertEquals(emptyList(), p.purge(listOf(outcome))) // not ARCHIVED -> not purged
    }
}
