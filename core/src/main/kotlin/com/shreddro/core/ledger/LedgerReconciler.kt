package com.shreddro.core.ledger

import com.shreddro.core.model.CloudProvider

/** A cloud ledger that can be read back for reconciliation. */
interface CloudLedger {
    val provider: CloudProvider

    /** Keys ([LedgerRecord.key]) of rows already in the cloud ledger; null when the backend cannot list rows (then nothing is appended). */
    suspend fun existingKeys(): Set<String>?

    suspend fun append(record: LedgerRecord)
}

/** Per-provider counts; every provider passed to [LedgerReconciler.reconcile] has an entry (possibly 0). */
data class ReconcileReport(val appended: Map<CloudProvider, Int>, val failed: Map<CloudProvider, Int>)

/**
 * Pushes local rows that a cloud ledger is missing. The local CSV is the
 * source of truth and is never modified here; the cloud only ever gains rows.
 */
class LedgerReconciler {

    /**
     * Pushes every local record whose key is missing from each cloud ledger;
     * local is canonical, never modified. Per-record failures are counted, not
     * thrown; continues with the next record/provider. Local duplicates (same
     * key) are appended once. A cloud whose [CloudLedger.existingKeys] returns
     * null is skipped; one whose listing throws counts every distinct local
     * record as failed for that provider.
     */
    suspend fun reconcile(local: List<LedgerRecord>, clouds: List<CloudLedger>): ReconcileReport {
        val distinctLocal = local.distinctBy { it.key }
        val appended = mutableMapOf<CloudProvider, Int>()
        val failed = mutableMapOf<CloudProvider, Int>()

        for (cloud in clouds) {
            var ok = 0
            var bad = 0
            val existing = try {
                cloud.existingKeys()
            } catch (e: Exception) {
                bad = distinctLocal.size
                null
            }
            if (existing != null) {
                for (record in distinctLocal) {
                    if (record.key in existing) continue
                    try {
                        cloud.append(record)
                        ok++
                    } catch (e: Exception) {
                        bad++
                    }
                }
            }
            appended[cloud.provider] = (appended[cloud.provider] ?: 0) + ok
            failed[cloud.provider] = (failed[cloud.provider] ?: 0) + bad
        }
        return ReconcileReport(appended = appended, failed = failed)
    }
}
