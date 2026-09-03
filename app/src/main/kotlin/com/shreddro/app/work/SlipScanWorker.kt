package com.shreddro.app.work

import android.content.Context
import android.util.Log
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.shreddro.app.ShreddroApp
import com.shreddro.app.notify.Notifications
import com.shreddro.core.pipeline.SlipStage
import java.util.concurrent.TimeUnit

/**
 * Background monitor (opt-in): periodically discovers fresh gallery images and
 * runs them through the pipeline up to the ARCHIVED stage.
 *
 * Purging is NOT performed here — MediaStore.createDeleteRequest() needs a
 * visible Activity to host the consent dialog, so archived-but-unpurged slips
 * are swept on the next app open (a notification nudges the user).
 */
class SlipScanWorker(
    context: Context,
    params: WorkerParameters,
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val app = applicationContext as? ShreddroApp ?: return Result.failure()
        val prefs = app.getSharedPreferences("shreddro_settings", Context.MODE_PRIVATE)

        return try {
            val images = app.storageCoordinator.findCandidates(app.lastScanEpoch)
            var archived = 0
            var review = 0
            for (image in images) {
                val candidate = runCatching {
                    app.storageCoordinator.loadCandidate(image)
                }.onFailure { Log.w(TAG, "load failed ${image.displayName}", it) }
                    .getOrNull() ?: continue
                val outcome = app.pipeline.process(candidate)
                Log.d(TAG, "${outcome.stage} ${image.bucket}/${image.displayName}" +
                    (outcome.error?.let { " (${it.message})" } ?: ""))
                when (outcome.stage) {
                    SlipStage.ARCHIVED -> {
                        archived++
                        // Purge needs a foreground Activity; park it for the
                        // Home hero's "Sweep now".
                        app.pendingPurge.add(
                            listOf(
                                com.shreddro.app.data.PendingPurge(
                                    candidate.mediaId, candidate.displayName,
                                ),
                            ),
                        )
                    }
                    SlipStage.NEEDS_REVIEW -> review++
                    else -> Unit
                }
            }
            app.advanceScanWatermark(images)
            if (archived > 0) {
                prefs.edit().putInt("pending_purge_count", archived).apply()
            }
            // Gate the drain on the queue itself, not on archive success —
            // LOGGED_LOCAL outcomes enqueue sheet ops without an archive.
            if (app.syncQueue.pendingCount() > 0) {
                SyncDrainWorker.scheduleNow(app)
            }
            Notifications.postScanDigest(app, archived, review)
            Result.success()
        } catch (e: Exception) {
            Log.e(TAG, "background scan failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "Shreddro.Scan"

        fun schedule(context: Context) {
            val request = PeriodicWorkRequestBuilder<SlipScanWorker>(30, TimeUnit.MINUTES)
                .setConstraints(
                    Constraints.Builder()
                        .setRequiredNetworkType(NetworkType.NOT_REQUIRED) // offline-first
                        .setRequiresBatteryNotLow(true)
                        .build(),
                )
                .build()
            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                "shreddro_slip_scan",
                ExistingPeriodicWorkPolicy.KEEP,
                request,
            )
        }
    }
}
