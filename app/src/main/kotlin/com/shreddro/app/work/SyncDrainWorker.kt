package com.shreddro.app.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shreddro.app.ShreddroApp
import java.util.concurrent.TimeUnit

/**
 * Drains the persistent sync queue (deferred/failed cloud ops) whenever the
 * network is back. Scheduled:
 *  - after every scan that leaves ops pending,
 *  - opportunistically on app start.
 *
 * WorkManager supplies the retry/backoff machinery; per-op attempt caps live
 * in core's SyncQueue so one poisoned record can't wedge the queue forever.
 */
class SyncDrainWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? ShreddroApp ?: return Result.failure()
        if (app.localModeForced) return Result.success() // user said no cloud

        val report = runCatching {
            app.syncQueue.drain(app.spreadsheetGateways, app.binaryGateways)
        }.getOrElse { return Result.retry() }

        return if (report.fullyDrained) Result.success() else Result.retry()
    }

    companion object {
        fun scheduleNow(context: Context) {
            val request = OneTimeWorkRequestBuilder<SyncDrainWorker>()
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.CONNECTED)
                        .build(),
                )
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, 30, TimeUnit.SECONDS)
                .build()
            WorkManager.getInstance(context).enqueueUniqueWork(
                "shreddro_sync_drain",
                ExistingWorkPolicy.KEEP,
                request,
            )
        }
    }
}
