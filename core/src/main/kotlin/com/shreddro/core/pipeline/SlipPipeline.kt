package com.shreddro.core.pipeline

import com.shreddro.core.gateway.MediaVault
import com.shreddro.core.gateway.SlipCandidate
import com.shreddro.core.gateway.SlipParseException
import com.shreddro.core.gateway.SlipParser
import com.shreddro.core.gateway.SlipValidator
import com.shreddro.core.model.TransactionSlip
import com.shreddro.core.registry.ProcessedRegistry
import com.shreddro.core.registry.ProcessedStatus
import com.shreddro.core.repo.TransactionRepository
import com.shreddro.core.review.ReviewItem
import com.shreddro.core.review.ReviewQueue

enum class SlipStage {
    DISCOVERED, ALREADY_PROCESSED, SKIPPED, VALIDATED, PARSED, NEEDS_REVIEW,
    LOGGED_LOCAL, ARCHIVED, PURGE_REQUESTED, PURGED, FAILED,
}

data class PipelineOutcome(
    val candidate: SlipCandidate,
    val stage: SlipStage,
    val slip: TransactionSlip? = null,
    val archivePath: String? = null,
    val error: Throwable? = null,
)

/**
 * Orchestrates one candidate image through the state machine:
 * dedup check -> gate -> LLM parse -> archive -> record (local + cloud/queue)
 * -> stage purge.
 *
 * Ordering rationale: the archive copy is made BEFORE recording so that queued
 * BINARY retries have a stable path to read from after the gallery original is
 * purged. An archive-only copy with no ledger row is harmless (private dir);
 * a purge without a ledger row would be data loss — hence the invariant below.
 *
 * Invariant: an original is eligible for purge only when the transaction row
 * is durably logged locally AND the archive copy is integrity-verified.
 *
 * Dedup: outcomes are recorded in the [ProcessedRegistry] by image SHA-256 so
 * rescans never re-invoke the Vision LLM or append duplicate rows — including
 * originals whose purge was declined and re-shared copies under new media ids.
 */
class SlipPipeline(
    private val validator: SlipValidator,
    private val parser: SlipParser,
    private val repository: TransactionRepository,
    private val vault: MediaVault,
    private val registry: ProcessedRegistry,
    private val reviewQueue: ReviewQueue? = null,
    private val parseRetries: Int = 1,
) {

    suspend fun process(candidate: SlipCandidate): PipelineOutcome {
        // Dedup: anything already concluded is never reprocessed implicitly.
        if (registry.statusOf(candidate.sha256) != null) {
            return PipelineOutcome(candidate, SlipStage.ALREADY_PROCESSED)
        }

        // Gate: cheap on-device check, no cloud cost for non-slips.
        val isSlip = try {
            validator.looksLikeBankSlip(candidate)
        } catch (e: Exception) {
            return PipelineOutcome(candidate, SlipStage.FAILED, error = e)
        }
        if (!isSlip) {
            registry.record(candidate.sha256, ProcessedStatus.SKIPPED)
            return PipelineOutcome(candidate, SlipStage.SKIPPED)
        }

        // Parse with bounded retry, then park for manual review.
        val slip = try {
            parseWithRetry(candidate)
        } catch (e: SlipParseException) {
            registry.record(candidate.sha256, ProcessedStatus.NEEDS_REVIEW)
            reviewQueue?.add(
                ReviewItem(
                    sha256 = candidate.sha256,
                    mediaId = candidate.mediaId,
                    fileName = candidate.displayName,
                    errorMessage = e.message ?: "parse failed",
                    createdAtEpochMs = System.currentTimeMillis(),
                ),
            )
            return PipelineOutcome(candidate, SlipStage.NEEDS_REVIEW, error = e)
        } catch (e: Exception) {
            return PipelineOutcome(candidate, SlipStage.FAILED, error = e)
        }

        return completeParsed(candidate, slip)
    }

    /**
     * User-initiated retry of a NEEDS_REVIEW image: clears the registry mark
     * and runs the full pipeline again (fresh LLM attempt).
     */
    suspend fun retry(candidate: SlipCandidate): PipelineOutcome {
        registry.clearForRetry(candidate.sha256)
        val outcome = process(candidate)
        // Anything but "still unreadable" resolves the review item: logged,
        // re-gated as a non-slip, or already concluded under another verdict
        // (e.g. SKIPPED on an earlier retry) — none of those may linger here.
        if (outcome.stage != SlipStage.NEEDS_REVIEW && outcome.stage != SlipStage.FAILED) {
            reviewQueue?.remove(candidate.sha256)
        }
        return outcome
    }

    /**
     * User keyed the fields in by hand: skip the LLM entirely and run the
     * post-parse tail (archive -> record -> registry) with their slip.
     */
    suspend fun resolveManually(candidate: SlipCandidate, slip: TransactionSlip): PipelineOutcome {
        registry.clearForRetry(candidate.sha256)
        val outcome = completeParsed(candidate, slip)
        if (outcome.stage == SlipStage.ARCHIVED || outcome.stage == SlipStage.LOGGED_LOCAL) {
            reviewQueue?.remove(candidate.sha256)
        }
        return outcome
    }

    /** Post-parse tail shared by automatic parsing and manual resolution. */
    private suspend fun completeParsed(
        candidate: SlipCandidate,
        slip: TransactionSlip,
    ): PipelineOutcome {
        // Archive first so deferred binary syncs outlive the purged original.
        val archivePath = try {
            vault.archive(candidate, slip.bankKey)
        } catch (e: Exception) {
            null // recorded below; slip stays LOGGED_LOCAL and unpurged
        }

        val record = repository.record(slip, candidate, archivePath)
        if (!record.safeToPurgeOriginal) {
            return PipelineOutcome(candidate, SlipStage.FAILED, slip = slip,
                archivePath = archivePath,
                error = IllegalStateException("Local ledger append failed; refusing to touch original"))
        }
        registry.record(candidate.sha256, ProcessedStatus.DONE)

        return if (archivePath != null) {
            PipelineOutcome(candidate, SlipStage.ARCHIVED, slip = slip, archivePath = archivePath)
        } else {
            PipelineOutcome(candidate, SlipStage.LOGGED_LOCAL, slip = slip)
        }
    }

    /** Batch-purge every ARCHIVED outcome via one platform consent dialog. */
    suspend fun purge(outcomes: List<PipelineOutcome>): List<String> {
        val eligible = outcomes.filter { it.stage == SlipStage.ARCHIVED }.map { it.candidate.mediaId }
        if (eligible.isEmpty()) return emptyList()
        return vault.requestPurge(eligible)
    }

    private suspend fun parseWithRetry(candidate: SlipCandidate): TransactionSlip {
        var last: SlipParseException? = null
        repeat(parseRetries + 1) {
            try {
                return parser.parse(candidate)
            } catch (e: SlipParseException) {
                last = e
            }
        }
        throw last ?: SlipParseException("Parse failed")
    }
}
