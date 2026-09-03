package com.shreddro.app

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.lifecycleScope
import androidx.compose.runtime.LaunchedEffect
import com.shreddro.app.storage.StorageCoordinator
import com.shreddro.app.ui.ReviewSection
import com.shreddro.app.ui.SettingsDialog
import com.shreddro.app.work.SyncDrainWorker
import com.shreddro.core.model.CloudProvider
import com.shreddro.core.model.TransactionSlip
import com.shreddro.core.pipeline.PipelineOutcome
import com.shreddro.core.pipeline.SlipStage
import com.shreddro.core.review.ReviewItem
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private val app get() = application as ShreddroApp
    private val coordinator: StorageCoordinator get() = app.storageCoordinator

    /**
     * THE consent bridge: MediaStore.createDeleteRequest() hands the
     * coordinator an IntentSender; this launcher shows the system dialog and
     * routes the user's decision back so the suspended purge can resume.
     */
    private val purgeConsentLauncher = registerForActivityResult(
        ActivityResultContracts.StartIntentSenderForResult(),
    ) { result ->
        coordinator.onPurgeResult(result.resultCode == Activity.RESULT_OK)
    }

    private val mediaPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { /* granted map inspected on next scan */ }

    private val googleAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.let { data ->
            lifecycleScope.launch {
                runCatching { app.auth.handleAuthorizationResponse(CloudProvider.GOOGLE, data) }
            }
        }
    }

    private val msAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.let { data ->
            lifecycleScope.launch {
                runCatching { app.auth.handleAuthorizationResponse(CloudProvider.MICROSOFT, data) }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator.bindLauncher(purgeConsentLauncher)
        requestMediaPermissions()

        setContent {
            var status by remember { mutableStateOf("Idle") }
            var localMode by remember { mutableStateOf(app.localModeForced) }
            var reviewItems by remember { mutableStateOf(emptyList<ReviewItem>()) }
            var showSettings by remember { mutableStateOf(false) }

            LaunchedEffect(Unit) { reviewItems = app.reviewQueue.list() }

            Column(Modifier.padding(24.dp)) {
                Text("Shreddro — Thai Bank Slip Sweeper")

                Button(onClick = {
                    googleAuthLauncher.launch(app.auth.authorizationIntent(CloudProvider.GOOGLE))
                }) { Text("Link Google Account") }

                Button(onClick = {
                    msAuthLauncher.launch(app.auth.authorizationIntent(CloudProvider.MICROSOFT))
                }) { Text("Link Microsoft Account") }

                Button(onClick = { showSettings = true }) { Text("Cloud & AI settings") }
                if (showSettings) {
                    SettingsDialog(
                        settings = app.settings,
                        onSaved = {
                            showSettings = false
                            app.rebuildSyncGraph()
                            status = "Settings saved"
                        },
                        onDismiss = { showSettings = false },
                    )
                }

                Text("Local Mode (no cloud)")
                Switch(checked = localMode, onCheckedChange = {
                    localMode = it
                    app.localModeForced = it
                })

                Button(onClick = {
                    status = "Scanning…"
                    lifecycleScope.launch {
                        status = runScan()
                        reviewItems = app.reviewQueue.list()
                    }
                }) { Text("Scan Gallery Now") }

                Text(status)

                ReviewSection(
                    items = reviewItems,
                    onRetry = { item ->
                        lifecycleScope.launch {
                            status = retryReview(item)
                            reviewItems = app.reviewQueue.list()
                        }
                    },
                    onManual = { item, slip ->
                        lifecycleScope.launch {
                            status = resolveReviewManually(item, slip)
                            reviewItems = app.reviewQueue.list()
                        }
                    },
                    onDismiss = { item ->
                        lifecycleScope.launch {
                            app.reviewQueue.remove(item.sha256)
                            reviewItems = app.reviewQueue.list()
                        }
                    },
                )
            }
        }
    }

    /** Re-runs the full pipeline (fresh LLM attempt) for a parked image. */
    private suspend fun retryReview(item: ReviewItem): String {
        val candidate = loadReviewCandidate(item)
            ?: return "Original image no longer available for ${item.fileName}"
        val outcome = app.pipeline.retry(candidate)
        if (outcome.stage == SlipStage.ARCHIVED) app.pipeline.purge(listOf(outcome))
        return "Retry of ${item.fileName}: ${outcome.stage}"
    }

    /** Records user-entered fields, skipping the LLM. */
    private suspend fun resolveReviewManually(item: ReviewItem, slip: TransactionSlip): String {
        val candidate = loadReviewCandidate(item)
            ?: return "Original image no longer available for ${item.fileName}"
        val outcome = app.pipeline.resolveManually(candidate, slip)
        if (outcome.stage == SlipStage.ARCHIVED) app.pipeline.purge(listOf(outcome))
        return "Saved ${item.fileName}: ${outcome.stage}"
    }

    private suspend fun loadReviewCandidate(item: ReviewItem) = runCatching {
        coordinator.loadCandidate(
            StorageCoordinator.GalleryImage(android.net.Uri.parse(item.mediaId), item.fileName),
        )
    }.getOrNull()

    /** Manual scan: discover -> pipeline each -> one batched purge consent. */
    private suspend fun runScan(): String {
        val since = getSharedPreferences("shreddro_settings", MODE_PRIVATE)
            .getLong("last_scan_epoch", 0L)
        val images = coordinator.findCandidates(since)

        val outcomes = mutableListOf<PipelineOutcome>()
        for (image in images) {
            val candidate = runCatching { coordinator.loadCandidate(image) }.getOrNull() ?: continue
            outcomes += app.pipeline.process(candidate)
        }

        val purged = app.pipeline.purge(outcomes)

        getSharedPreferences("shreddro_settings", MODE_PRIVATE)
            .edit().putLong("last_scan_epoch", System.currentTimeMillis() / 1000).apply()

        // Kick the drain worker for any cloud ops that deferred or failed.
        if (app.syncQueue.pendingCount() > 0) {
            SyncDrainWorker.scheduleNow(this)
        }

        val archived = outcomes.count { it.stage == SlipStage.ARCHIVED }
        val skipped = outcomes.count { it.stage == SlipStage.SKIPPED }
        val review = outcomes.count { it.stage == SlipStage.NEEDS_REVIEW }
        val dedup = outcomes.count { it.stage == SlipStage.ALREADY_PROCESSED }
        return "Scanned ${images.size}: $archived archived, ${purged.size} purged, " +
            "$skipped skipped, $review need review, $dedup already done"
    }

    private fun requestMediaPermissions() {
        val perms = when {
            Build.VERSION.SDK_INT >= 34 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.READ_MEDIA_VISUAL_USER_SELECTED,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            Build.VERSION.SDK_INT >= 33 -> arrayOf(
                Manifest.permission.READ_MEDIA_IMAGES,
                Manifest.permission.POST_NOTIFICATIONS,
            )
            else -> arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        mediaPermissionLauncher.launch(perms)
    }
}
