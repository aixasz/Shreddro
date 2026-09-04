package com.shreddro.core.repo

import com.shreddro.core.gateway.BinaryStorageGateway
import com.shreddro.core.gateway.LedgerSink
import com.shreddro.core.gateway.SlipCandidate
import com.shreddro.core.gateway.SpreadsheetGateway
import com.shreddro.core.gateway.SyncStateProvider
import com.shreddro.core.model.CloudProvider
import com.shreddro.core.model.RecordResult
import com.shreddro.core.model.SyncOutcome
import com.shreddro.core.model.TransactionSlip
import com.shreddro.core.queue.OpKind
import com.shreddro.core.queue.PendingOp
import com.shreddro.core.queue.SyncQueue
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * Hybrid synchronization router.
 *
 * Offline-first contract:
 *  1. The local ledger append happens FIRST and ALWAYS — it is the durability
 *     anchor that later authorizes purging the gallery original.
 *  2. Cloud fan-out (spreadsheet row + raw binary) runs concurrently per linked
 *     provider; the row and the binary succeed or fail independently.
 *  3. Any deferred or failed cloud op is persisted into the [SyncQueue] so the
 *     background drain worker retries it — EXCEPT under user-forced Local Mode,
 *     which is an explicit "keep my data off the cloud" choice, not an outage.
 */
class TransactionRepository(
    private val ledger: LedgerSink,
    private val spreadsheets: Map<CloudProvider, SpreadsheetGateway>,
    private val binaryStores: Map<CloudProvider, BinaryStorageGateway>,
    private val syncStateProvider: SyncStateProvider,
    private val syncQueue: SyncQueue? = null,
) {

    /**
     * @param archivePath private-archive copy of the image, when it exists —
     *        queued BINARY retries read from it because the gallery original
     *        may be purged before the queue drains.
     */
    suspend fun record(
        slip: TransactionSlip,
        candidate: SlipCandidate,
        archivePath: String? = null,
    ): RecordResult {
        val local = try {
            ledger.append(slip, candidate.mediaId, candidate.displayName)
            SyncOutcome.Success(CloudProvider.LOCAL_CSV)
        } catch (e: Exception) {
            SyncOutcome.Failure(CloudProvider.LOCAL_CSV, e)
        }

        val state = syncStateProvider.current()
        val active = state.activeCloudTargets
        val inactive = state.linkedProviders - CloudProvider.LOCAL_CSV - active

        // Offline (not user-chosen Local Mode): persist the full op set now.
        if (!state.localModeForced) {
            syncQueue?.enqueue(inactive.flatMap { opsFor(it, slip, candidate, archivePath) })
        }
        val deferred = inactive.map { SyncOutcome.Deferred(it) }

        val results = coroutineScope {
            active.map { provider ->
                async { syncOne(provider, slip, candidate, archivePath) }
            }.map { it.await() }
        }

        return RecordResult(local = local, cloud = results + deferred)
    }

    /** Row and binary attempted separately; each failure is queued individually. */
    private suspend fun syncOne(
        provider: CloudProvider,
        slip: TransactionSlip,
        candidate: SlipCandidate,
        archivePath: String?,
    ): SyncOutcome {
        var firstError: Exception? = null
        val failedOps = mutableListOf<PendingOp>()

        // The row cites the image by its CLOUD name (the binary adapter may
        // transcode/rename), so rows and files stay mappable either way.
        val imageName = binaryStores[provider]?.cloudFileName(candidate.displayName)
            ?: candidate.displayName
        spreadsheets[provider]?.let { gateway ->
            try {
                gateway.appendRow(slip, imageName)
            } catch (e: Exception) {
                firstError = e
                failedOps += op(provider, OpKind.SHEET_ROW, slip, candidate, null)
            }
        }
        binaryStores[provider]?.let { gateway ->
            try {
                gateway.upload(candidate.bytes, candidate.displayName, slip.bankKey)
            } catch (e: Exception) {
                if (firstError == null) firstError = e
                if (archivePath != null) {
                    failedOps += op(provider, OpKind.BINARY, slip, candidate, archivePath)
                }
            }
        }

        syncQueue?.enqueue(failedOps)
        return firstError?.let { SyncOutcome.Failure(provider, it) }
            ?: SyncOutcome.Success(provider)
    }

    private fun opsFor(
        provider: CloudProvider,
        slip: TransactionSlip,
        candidate: SlipCandidate,
        archivePath: String?,
    ): List<PendingOp> = buildList {
        if (spreadsheets.containsKey(provider)) {
            add(op(provider, OpKind.SHEET_ROW, slip, candidate, null))
        }
        if (binaryStores.containsKey(provider) && archivePath != null) {
            add(op(provider, OpKind.BINARY, slip, candidate, archivePath))
        }
    }

    private fun op(
        provider: CloudProvider,
        kind: OpKind,
        slip: TransactionSlip,
        candidate: SlipCandidate,
        archivePath: String?,
    ) = PendingOp(
        id = "${candidate.sha256}:${provider.name}:${kind.name}",
        provider = provider,
        kind = kind,
        slip = slip,
        sourceMediaId = candidate.mediaId,
        fileName = candidate.displayName,
        archivePath = archivePath,
        createdAtEpochMs = System.currentTimeMillis(),
    )
}
