package com.shreddro.app

import android.Manifest
import android.app.Activity
import android.os.Build
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
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
import com.shreddro.app.data.LocalXlsxLedger
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
                app.provisionCloud(listOf(CloudProvider.GOOGLE))
                app.reconcileCloud(listOf(CloudProvider.GOOGLE))
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
                app.provisionCloud(listOf(CloudProvider.MICROSOFT))
                app.reconcileCloud(listOf(CloudProvider.MICROSOFT))
                refreshTick++
            }
        }
    }

    /** Bumped to trigger recomposition-driven data reloads. */
    private var refreshTick by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // System bars follow the app's light/dark palette instead of staying
        // white over a dark surface; Scaffold already pads for insets.
        enableEdgeToEdge()
        coordinator.bindLauncher(purgeConsentLauncher)
        requestMediaPermissions()

        val ledgerReader = LedgerReader(app.csvSink.ledgerFile())

        // Linked accounts get their folders/sheets verified on every launch
        // (cheap find-by-name calls) so a bank added offline shows up in the
        // cloud before its first synced slip, then any rows the cloud is
        // missing are pushed. Without a linked account the local .xlsx
        // mirror is brought up to date with the CSV instead.
        lifecycleScope.launch {
            app.provisionCloud()
            app.reconcileCloud()
            app.refreshLocalLedgerIfUnlinked()
            refreshTick++
        }

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
                var retryingAll by remember { mutableStateOf(false) }

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

                            Tab.LEDGER -> LedgerScreen(
                                entries = ledger,
                                linked = linked,
                                onOpenLocalLedger = ::openLocalLedger,
                            )

                            Tab.REVIEW -> ReviewScreen(
                                items = reviewItems,
                                retrying = retryingAll,
                                onRetry = { item ->
                                    lifecycleScope.launch {
                                        retryReview(item)
                                        refreshTick++
                                    }
                                },
                                onRetryAll = {
                                    retryingAll = true
                                    lifecycleScope.launch {
                                        retryAllReviews(reviewItems)
                                        retryingAll = false
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
                                cloudLinks = com.shreddro.app.ui.CloudLinks(
                                    googleSheet = app.settings.googleSheetUrl,
                                    driveFolder = app.settings.googleDriveFolderUrl,
                                    excelWorkbook = app.settings.excelWorkbookUrl,
                                    oneDriveFolder = app.settings.oneDriveFolderUrl,
                                ),
                                onLinkGoogle = {
                                    // A build without an OAuth client id (local
                                    // debug builds) must not crash in AppAuth.
                                    if (com.shreddro.app.auth.AuthConfig.googleClientId.isBlank()) {
                                        notConfigured("Google")
                                    } else {
                                        googleAuthLauncher.launch(
                                            app.auth.authorizationIntent(CloudProvider.GOOGLE),
                                        )
                                    }
                                },
                                onLinkMicrosoft = {
                                    if (com.shreddro.app.auth.AuthConfig.msClientId.isBlank()) {
                                        notConfigured("Microsoft")
                                    } else {
                                        msAuthLauncher.launch(
                                            app.auth.authorizationIntent(CloudProvider.MICROSOFT),
                                        )
                                    }
                                },
                                onOpenSettings = { showSettings = true },
                                onLocalModeChange = {
                                    localMode = it
                                    app.localModeForced = it
                                    if (!it) lifecycleScope.launch { drainIfPending() }
                                },
                                onOpenUrl = ::openUrl,
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
                        onRescanAll = {
                            showSettings = false
                            app.resetScanWatermark()
                            tab = Tab.HOME
                            scanning = true
                            lifecycleScope.launch {
                                // Images the gate once rejected get a fresh
                                // look (detection rules improve); logged and
                                // parked ones stay deduped.
                                app.registry.clearSkipped()
                                summary = runScan()
                                scanning = false
                                refreshTick++
                            }
                        },
                        onDismiss = { showSettings = false },
                    )
                }
            }
        }
    }

    /** Manual scan: discover -> pipeline each -> record archives for sweeping. */
    private suspend fun runScan(): ScanSummary {
        val images = coordinator.findCandidates(app.lastScanEpoch)

        // Keep only stage tallies + purge ids. Retaining every PipelineOutcome
        // (each holding the candidate's full image bytes) until the end of the
        // scan blew the heap on a 200-image gallery.
        val stageCounts = mutableMapOf<SlipStage, Int>()
        val archivedForPurge = mutableListOf<PendingPurge>()
        for (image in images) {
            val candidate = runCatching { coordinator.loadCandidate(image) }
                .onFailure { Log.w(TAG, "load failed ${image.displayName}", it) }
                .getOrNull() ?: continue
            val outcome = app.pipeline.process(candidate)
            Log.d(TAG, "${outcome.stage} ${image.bucket}/${image.displayName}" +
                (outcome.error?.let { " (${it.message})" } ?: ""))
            stageCounts[outcome.stage] = (stageCounts[outcome.stage] ?: 0) + 1
            if (outcome.stage == SlipStage.ARCHIVED) {
                archivedForPurge += PendingPurge(candidate.mediaId, candidate.displayName)
            }
        }

        app.pendingPurge.add(archivedForPurge)
        val purged = sweepPending()

        app.advanceScanWatermark(images)
        drainIfPending()
        // Best-effort: rows just logged reach every linked cloud even if one
        // direct sync failed (failures are logged inside, never thrown).
        app.reconcileCloud()

        return ScanSummary(
            scanned = images.size,
            archived = stageCounts[SlipStage.ARCHIVED] ?: 0,
            purged = purged,
            skipped = stageCounts[SlipStage.SKIPPED] ?: 0,
            needsReview = stageCounts[SlipStage.NEEDS_REVIEW] ?: 0,
            alreadyDone = stageCounts[SlipStage.ALREADY_PROCESSED] ?: 0,
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

    /**
     * Retries every parked slip in one pass, then shows ONE purge consent
     * dialog for everything that archived (per-item dialogs for 30+ slips
     * would be hostile). Items that still fail simply stay in the queue.
     */
    private suspend fun retryAllReviews(items: List<ReviewItem>) {
        val archived = mutableListOf<PendingPurge>()
        for (item in items) {
            val candidate = loadReviewCandidate(item) ?: continue
            val outcome = runCatching { app.pipeline.retry(candidate) }
                .onFailure { Log.w(TAG, "retry failed ${item.fileName}", it) }
                .getOrNull() ?: continue
            Log.d(TAG, "RETRY ${outcome.stage} ${item.fileName}" +
                (outcome.error?.let { " (${it.message})" } ?: ""))
            if (outcome.stage == SlipStage.ARCHIVED) {
                archived += PendingPurge(item.mediaId, item.fileName)
            }
        }
        app.pendingPurge.add(archived)
        sweepPending()
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

    /**
     * Opens a cloud link in its native app when installed — Google Sheets /
     * Drive, Excel (via the `ms-excel:ofe|u|` scheme), OneDrive — and falls
     * back to a plain ACTION_VIEW, which lands in the browser. Package names
     * must be declared in the manifest `<queries>` for resolveActivity to see
     * them on Android 11+.
     */
    private fun openUrl(url: String) {
        val uri = android.net.Uri.parse(url)
        val candidates = buildList {
            when {
                url.contains("docs.google.com/spreadsheets") ->
                    add(viewIntent(uri).setPackage("com.google.android.apps.docs.editors.sheets"))
                url.contains("drive.google.com") ->
                    add(viewIntent(uri).setPackage("com.google.android.apps.docs"))
                url.contains("1drv.ms") || url.contains("onedrive.live.com") ||
                    url.contains("sharepoint.com") || url.contains("my.microsoftpersonalcontent.com") -> {
                    if (url.contains(".xlsx", ignoreCase = true) || url.contains("/edit")) {
                        add(viewIntent(android.net.Uri.parse("ms-excel:ofe|u|$url")))
                    }
                    add(viewIntent(uri).setPackage("com.microsoft.skydrive"))
                }
            }
            add(viewIntent(uri))
        }
        for (intent in candidates) {
            if (intent.resolveActivity(packageManager) == null) continue
            if (runCatching { startActivity(intent) }.isSuccess) return
        }
    }

    private fun viewIntent(uri: android.net.Uri) =
        android.content.Intent(android.content.Intent.ACTION_VIEW, uri)
            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)

    /**
     * Opens `Documents/Shreddro Transactions.xlsx` (the ledger a user without a
     * cloud account gets) in whatever spreadsheet viewer is installed, via the
     * FileProvider; when nothing can VIEW an .xlsx, falls back to a share
     * chooser (ACTION_SEND) so the file can still leave the phone.
     */
    private fun openLocalLedger() {
        lifecycleScope.launch {
            val file = app.localXlsx.file
            if (!file.exists()) app.refreshLocalLedger()
            if (!file.exists()) {
                android.widget.Toast.makeText(
                    this@MainActivity, "Couldn't write the Excel file.", android.widget.Toast.LENGTH_LONG,
                ).show()
                return@launch
            }
            val uri = androidx.core.content.FileProvider.getUriForFile(
                this@MainActivity, "$packageName.files", file,
            )
            val view = android.content.Intent(android.content.Intent.ACTION_VIEW)
                .setDataAndType(uri, LocalXlsxLedger.MIME)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (view.resolveActivity(packageManager) != null && runCatching { startActivity(view) }.isSuccess) {
                return@launch
            }
            val send = android.content.Intent(android.content.Intent.ACTION_SEND)
                .setType(LocalXlsxLedger.MIME)
                .putExtra(android.content.Intent.EXTRA_STREAM, uri)
                .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION)
            runCatching { startActivity(android.content.Intent.createChooser(send, LocalXlsxLedger.FILE_NAME)) }
                .onFailure { Log.w(TAG, "no app can open or share the local ledger", it) }
        }
    }

    private suspend fun loadReviewCandidate(item: ReviewItem) = runCatching {
        coordinator.loadCandidate(
            StorageCoordinator.GalleryImage(android.net.Uri.parse(item.mediaId), item.fileName),
        )
    }.getOrNull()

    private fun notConfigured(provider: String) {
        android.widget.Toast.makeText(
            this,
            "$provider sign-in isn't configured in this build (no OAuth client id).",
            android.widget.Toast.LENGTH_LONG,
        ).show()
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

    private companion object {
        const val TAG = "Shreddro.Scan"
    }
}
