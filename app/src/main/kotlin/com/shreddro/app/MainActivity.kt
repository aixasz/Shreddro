package com.shreddro.app

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.lifecycleScope
import com.shreddro.app.data.LedgerEntry
import com.shreddro.app.data.LedgerReader
import com.shreddro.app.data.PendingPurge
import com.shreddro.app.storage.StorageCoordinator
import com.shreddro.app.ui.AccountScreen
import com.shreddro.app.ui.HomeScreen
import com.shreddro.app.ui.HomeState
import com.shreddro.app.ui.LedgerScreen
import com.shreddro.app.ui.ReviewScreen
import com.shreddro.app.ui.ScanSummary
import com.shreddro.app.ui.SettingsDialog
import com.shreddro.app.ui.ShreddroBottomNav
import com.shreddro.app.ui.Tab
import com.shreddro.app.ui.theme.ShreddroTheme
import com.shreddro.app.work.SyncDrainWorker
import com.shreddro.core.model.CloudProvider
import com.shreddro.core.model.TransactionSlip
import com.shreddro.core.pipeline.PipelineOutcome
import com.shreddro.core.pipeline.SlipStage
import com.shreddro.core.review.ReviewItem
import kotlinx.coroutines.launch
import java.time.YearMonth

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
                refreshTick++
            }
        }
    }

    private val msAuthLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        result.data?.let { data ->
            lifecycleScope.launch {
                runCatching { app.auth.handleAuthorizationResponse(CloudProvider.MICROSOFT, data) }
                refreshTick++
            }
        }
    }

    /** Bumped to trigger recomposition-driven data reloads. */
    private var refreshTick by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        coordinator.bindLauncher(purgeConsentLauncher)
        requestMediaPermissions()

        val ledgerReader = LedgerReader(app.csvSink.ledgerFile())

        setContent {
            ShreddroTheme {
                var tab by remember { mutableStateOf(Tab.HOME) }
                var scanning by remember { mutableStateOf(false) }
                var summary by remember { mutableStateOf<ScanSummary?>(null) }
                var reviewItems by remember { mutableStateOf(emptyList<ReviewItem>()) }
                var pendingSweep by remember { mutableStateOf(0) }
                var ledger by remember { mutableStateOf(emptyList<LedgerEntry>()) }
                var localMode by remember { mutableStateOf(app.localModeForced) }
                var showSettings by remember { mutableStateOf(false) }

                LaunchedEffect(refreshTick) {
                    reviewItems = app.reviewQueue.list()
                    pendingSweep = app.pendingPurge.list().size
                    ledger = ledgerReader.readAll()
                }

                val month = YearMonth.now().toString()
                val monthEntries = ledgerReader.monthOf(ledger, month)
                val linked = app.auth.linkedProviders()

                Scaffold(
                    bottomBar = {
                        ShreddroBottomNav(tab, reviewItems.size) { tab = it }
                    },
                ) { pad ->
                    androidx.compose.foundation.layout.Box(Modifier.padding(pad)) {
                        when (tab) {
                            Tab.HOME -> HomeScreen(
                                state = HomeState(
                                    pendingSweep = pendingSweep,
                                    scanning = scanning,
                                    summary = summary,
                                    monthTotal = monthEntries.sumOf { it.amount },
                                    monthCount = monthEntries.size,
                                    googleLinked = CloudProvider.GOOGLE in linked,
                                    microsoftLinked = CloudProvider.MICROSOFT in linked,
                                    recent = ledger.take(3),
                                ),
                                onScan = {
                                    scanning = true
                                    lifecycleScope.launch {
                                        summary = runScan()
                                        scanning = false
                                        refreshTick++
                                    }
                                },
                                onSweep = {
                                    lifecycleScope.launch {
                                        sweepPending()
                                        refreshTick++
                                    }
                                },
                            )

                            Tab.LEDGER -> LedgerScreen(ledger)

                            Tab.REVIEW -> ReviewScreen(
                                items = reviewItems,
                                onRetry = { item ->
                                    lifecycleScope.launch {
                                        retryReview(item)
                                        refreshTick++
                                    }
                                },
                                onManual = { item, slip ->
                                    lifecycleScope.launch {
                                        resolveReviewManually(item, slip)
                                        refreshTick++
                                    }
                                },
                                onDismiss = { item ->
                                    lifecycleScope.launch {
                                        app.reviewQueue.remove(item.sha256)
                                        refreshTick++
                                    }
                                },
                            )

                            Tab.ACCOUNT -> AccountScreen(
                                googleLinked = CloudProvider.GOOGLE in linked,
                                microsoftLinked = CloudProvider.MICROSOFT in linked,
                                localMode = localMode,
                                onLinkGoogle = {
                                    googleAuthLauncher.launch(
                                        app.auth.authorizationIntent(CloudProvider.GOOGLE),
                                    )
                                },
                                onLinkMicrosoft = {
                                    msAuthLauncher.launch(
                                        app.auth.authorizationIntent(CloudProvider.MICROSOFT),
                                    )
                                },
                                onOpenSettings = { showSettings = true },
                                onLocalModeChange = {
                                    localMode = it
                                    app.localModeForced = it
                                    if (!it) lifecycleScope.launch { drainIfPending() }
                                },
                            )
                        }
                    }
                }

                if (showSettings) {
                    SettingsDialog(
                        settings = app.settings,
                        onSaved = {
                            showSettings = false
                            app.rebuildSyncGraph()
                            refreshTick++
                        },
                        onDismiss = { showSettings = false },
                    )
                }
            }
        }
    }

    /** Manual scan: discover -> pipeline each -> record archives for sweeping. */
    private suspend fun runScan(): ScanSummary {
        val since = getSharedPreferences("shreddro_settings", MODE_PRIVATE)
            .getLong("last_scan_epoch", 0L)
        val images = coordinator.findCandidates(since)

        val outcomes = mutableListOf<PipelineOutcome>()
        for (image in images) {
            val candidate = runCatching { coordinator.loadCandidate(image) }.getOrNull() ?: continue
            outcomes += app.pipeline.process(candidate)
        }

        app.pendingPurge.add(
            outcomes.filter { it.stage == SlipStage.ARCHIVED }
                .map { PendingPurge(it.candidate.mediaId, it.candidate.displayName) },
        )
        val purged = sweepPending()

        getSharedPreferences("shreddro_settings", MODE_PRIVATE)
            .edit().putLong("last_scan_epoch", System.currentTimeMillis() / 1000).apply()
        drainIfPending()

        return ScanSummary(
            scanned = images.size,
            archived = outcomes.count { it.stage == SlipStage.ARCHIVED },
            purged = purged,
            skipped = outcomes.count { it.stage == SlipStage.SKIPPED },
            needsReview = outcomes.count { it.stage == SlipStage.NEEDS_REVIEW },
            alreadyDone = outcomes.count { it.stage == SlipStage.ALREADY_PROCESSED },
        )
    }

    /** One batched consent dialog for everything awaiting sweep. */
    private suspend fun sweepPending(): Int {
        val pending = app.pendingPurge.list()
        if (pending.isEmpty()) return 0
        val purged = coordinator.requestPurge(pending.map { it.mediaId })
        app.pendingPurge.remove(purged)
        return purged.size
    }

    /** Re-runs the full pipeline (fresh OCR attempt) for a parked image. */
    private suspend fun retryReview(item: ReviewItem) {
        val candidate = loadReviewCandidate(item) ?: return
        val outcome = app.pipeline.retry(candidate)
        if (outcome.stage == SlipStage.ARCHIVED) {
            app.pendingPurge.add(listOf(PendingPurge(item.mediaId, item.fileName)))
            sweepPending()
        }
        drainIfPending()
    }

    /** Records user-entered fields, skipping OCR. */
    private suspend fun resolveReviewManually(item: ReviewItem, slip: TransactionSlip) {
        val candidate = loadReviewCandidate(item) ?: return
        val outcome = app.pipeline.resolveManually(candidate, slip)
        if (outcome.stage == SlipStage.ARCHIVED) {
            app.pendingPurge.add(listOf(PendingPurge(item.mediaId, item.fileName)))
            sweepPending()
        }
        drainIfPending()
    }

    /** Any path that can enqueue cloud ops must also arm the drain worker. */
    private suspend fun drainIfPending() {
        if (app.syncQueue.pendingCount() > 0) SyncDrainWorker.scheduleNow(this)
    }

    private suspend fun loadReviewCandidate(item: ReviewItem) = runCatching {
        coordinator.loadCandidate(
            StorageCoordinator.GalleryImage(android.net.Uri.parse(item.mediaId), item.fileName),
        )
    }.getOrNull()

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
