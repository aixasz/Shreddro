package com.shreddro.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.shreddro.app.auth.AppAuthManager
import com.shreddro.app.data.AndroidCsvSink
import com.shreddro.app.data.AppSettings
import com.shreddro.app.data.FileArchiveReader
import com.shreddro.app.data.FileProcessedStore
import com.shreddro.app.data.FileReviewStore
import com.shreddro.app.data.FileSyncQueueStore
import com.shreddro.app.data.LedgerReader
import com.shreddro.app.data.LocalXlsxLedger
import com.shreddro.app.net.AppsScriptSheetGateway
import com.shreddro.app.net.Clients
import com.shreddro.app.net.CloudProvisioner
import com.shreddro.app.net.CompressedUploadGateway
import com.shreddro.app.net.DriveBinaryGateway
import com.shreddro.app.net.GoogleDriveFiles
import com.shreddro.app.net.GoogleSheetsGateway
import com.shreddro.app.net.GraphExcelGateway
import com.shreddro.app.net.OneDriveBinaryGateway
import com.shreddro.app.ocr.MlKitSlipValidator
import com.shreddro.app.ocr.OfflineSlipParser
import com.shreddro.app.storage.StorageCoordinator
import com.shreddro.app.work.SyncDrainWorker
import com.shreddro.core.gateway.BinaryStorageGateway
import com.shreddro.core.gateway.LedgerSink
import com.shreddro.core.gateway.SpreadsheetGateway
import com.shreddro.core.gateway.SyncStateProvider
import com.shreddro.core.ledger.CloudLedger
import com.shreddro.core.ledger.LedgerReconciler
import com.shreddro.core.model.CloudProvider
import com.shreddro.core.model.TransactionSlip
import com.shreddro.core.model.SyncState
import com.shreddro.core.pipeline.SlipPipeline
import com.shreddro.core.queue.OpKind
import com.shreddro.core.queue.SyncQueue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.shreddro.core.registry.ProcessedRegistry
import com.shreddro.core.repo.TransactionRepository
import com.shreddro.core.review.ReviewQueue

/**
 * Manual composition root (Phase 1). Deliberately DI-framework-free so the
 * graph maps 1:1 onto a future Koin/KMP module definition.
 *
 * Credentials model (public-repo safe): OAuth client ids are build-time
 * identifiers (not secrets); the Gemini key and the user's Apps Script
 * deployment are runtime [AppSettings], entered in-app and never shipped in
 * the APK. [rebuildSyncGraph] re-wires the gateways after a settings change.
 */
class ShreddroApp : Application() {

    lateinit var auth: AppAuthManager
        private set
    lateinit var settings: AppSettings
        private set
    lateinit var storageCoordinator: StorageCoordinator
        private set
    lateinit var pipeline: SlipPipeline
        private set
    lateinit var syncQueue: SyncQueue
        private set
    lateinit var registry: ProcessedRegistry
        private set
    lateinit var reviewQueue: ReviewQueue
        private set
    lateinit var pendingPurge: com.shreddro.app.data.PendingPurgeStore
        private set
    lateinit var csvSink: AndroidCsvSink
        private set
    lateinit var localXlsx: LocalXlsxLedger
        private set
    private lateinit var syncQueueStore: FileSyncQueueStore
    lateinit var spreadsheetGateways: Map<CloudProvider, SpreadsheetGateway>
        private set
    lateinit var binaryGateways: Map<CloudProvider, BinaryStorageGateway>
        private set

    private val prefs by lazy { getSharedPreferences("shreddro_settings", Context.MODE_PRIVATE) }

    var localModeForced: Boolean
        get() = prefs.getBoolean("local_mode", false)
        set(value) = prefs.edit().putBoolean("local_mode", value).apply()

    /** Gallery scan watermark: MediaStore DATE_ADDED (epoch seconds) of the newest image seen. */
    var lastScanEpoch: Long
        get() = prefs.getLong("last_scan_epoch", 0L)
        private set(value) = prefs.edit().putLong("last_scan_epoch", value).apply()

    /**
     * Advances the watermark to the newest image actually enumerated — never
     * to "now". Stamping "now" after an empty scan (permission not yet
     * granted, MediaStore still indexing) would hide every older image from
     * all future scans.
     */
    fun advanceScanWatermark(images: List<StorageCoordinator.GalleryImage>) {
        val newest = images.maxOfOrNull { it.dateAddedEpochSeconds } ?: return
        if (newest > lastScanEpoch) lastScanEpoch = newest
    }

    /** Forces the next scan to revisit the whole gallery (dedup registry still applies). */
    fun resetScanWatermark() {
        lastScanEpoch = 0L
    }

    override fun onCreate() {
        super.onCreate()
        migrateScanState()
        auth = AppAuthManager(this)
        settings = AppSettings(this)
        storageCoordinator = StorageCoordinator(this)
        syncQueueStore = FileSyncQueueStore(this)
        syncQueue = SyncQueue(syncQueueStore, FileArchiveReader())
        registry = ProcessedRegistry(FileProcessedStore(this))
        reviewQueue = ReviewQueue(FileReviewStore(this))
        pendingPurge = com.shreddro.app.data.PendingPurgeStore(this)
        csvSink = AndroidCsvSink(this)
        localXlsx = LocalXlsxLedger(this)

        rebuildSyncGraph()

        // Opportunistic drain of anything left over from previous sessions.
        SyncDrainWorker.scheduleNow(this)
    }

    /**
     * (Re)builds gateways, repository and pipeline from current [settings].
     * Call after the user saves new credentials so they take effect
     * immediately — no restart needed.
     */
    fun rebuildSyncGraph() {
        val syncStateProvider = object : SyncStateProvider {
            override fun current() = SyncState(
                linkedProviders = auth.linkedProviders(),
                localModeForced = localModeForced,
                online = isOnline(),
            )
        }

        // Cloud layout, both providers: Shreddro/<bank>/<slip image> plus ONE
        // central Shreddro/Shreddro Transactions (Google Sheet / .xlsx) with a
        // bank column. Folders and files are found by name, created if missing.
        val driveFiles = GoogleDriveFiles(Clients.okHttp)
        spreadsheetGateways = mapOf(
            CloudProvider.GOOGLE to if (settings.appsScriptUrl.isNotBlank()) {
                // Legacy opt-in: a user-deployed Apps Script master sheet.
                AppsScriptSheetGateway(
                    Clients.appsScriptApi,
                    settings.appsScriptUrl,
                    settings.appsScriptSecret,
                    onSheetUrl = { settings.googleSheetUrl = it },
                )
            } else {
                GoogleSheetsGateway(
                    auth, Clients.okHttp, driveFiles,
                    onSheetUrl = { settings.googleSheetUrl = it },
                    onFolderUrl = { settings.googleDriveFolderUrl = it },
                )
            },
            CloudProvider.MICROSOFT to GraphExcelGateway(
                Clients.graphApi, auth,
                emptyWorkbook = { assets.open("empty_workbook.xlsx").use { it.readBytes() } },
                onWorkbookUrl = { settings.excelWorkbookUrl = it },
                onFolderUrl = { settings.oneDriveFolderUrl = it },
            ),
        )
        // Cloud copies go up as downsized JPEGs (toggle in Settings); the local
        // archive the purge invariant hashes against is never touched.
        val compressed = { settings.compressUploads }
        binaryGateways = mapOf(
            CloudProvider.GOOGLE to CompressedUploadGateway(
                DriveBinaryGateway(
                    auth, driveFiles,
                    onFolderUrl = { settings.googleDriveFolderUrl = it },
                ),
                compressed,
            ),
            CloudProvider.MICROSOFT to CompressedUploadGateway(
                OneDriveBinaryGateway(
                    Clients.graphApi, auth,
                    onFolderUrl = { settings.oneDriveFolderUrl = it },
                ),
                compressed,
            ),
        )

        val repository = TransactionRepository(
            // CSV first (the purge anchor); with no cloud linked, the local
            // .xlsx mirror is regenerated from it after every row.
            ledger = MirroringLedgerSink(csvSink) { refreshLocalLedgerIfUnlinked() },
            spreadsheets = spreadsheetGateways,
            binaryStores = binaryGateways,
            syncStateProvider = syncStateProvider,
            syncQueue = syncQueue,
        )

        pipeline = SlipPipeline(
            validator = MlKitSlipValidator(),
            // 100% on-device parsing: Tesseract tha+eng + QR + template rules.
            // No network, no API key; slip images never leave the device.
            parser = OfflineSlipParser(this),
            repository = repository,
            vault = storageCoordinator,
            registry = registry,
            reviewQueue = reviewQueue,
        )
    }

    /**
     * Makes sure the linked account already shows the Shreddro layout —
     * root folder, the central transactions file, and a folder for every bank
     * in the local ledger — before any new slip arrives. Safe to call repeatedly.
     * Failures are logged, never thrown: provisioning is a convenience, the
     * next slip sync will create whatever is still missing.
     */
    suspend fun provisionCloud(providers: Collection<CloudProvider> = auth.linkedProviders()) {
        if (localModeForced) return
        val banks = LedgerReader(csvSink.ledgerFile()).readAll()
            .map { TransactionSlip.bankKeyOf(it.bankName) }
            .distinct()
        for (provider in providers) {
            val gateway = spreadsheetGateways[provider] as? CloudProvisioner ?: continue
            runCatching { gateway.provision(banks) }
                .onFailure { Log.w(TAG_UPLOAD, "provision $provider failed", it) }
        }
    }

    /**
     * Pushes every local row a linked cloud ledger is missing. The local CSV
     * is canonical and never modified; clouds only ever gain rows.
     *
     * Queued cloud ops are drained first so a row whose direct sync failed is
     * replayed by the queue (once) and then *seen* by the reconciler, instead
     * of being appended here and again by the drain worker. A provider that
     * still has queued rows afterwards is skipped this round — the worker
     * keeps retrying, and the next launch/scan reconciles it.
     * Best-effort: logged, never thrown.
     */
    suspend fun reconcileCloud(providers: Collection<CloudProvider> = auth.linkedProviders()) {
        if (localModeForced) return
        val linked = providers.filter { it in auth.linkedProviders() }
        if (linked.isEmpty()) return
        withContext(Dispatchers.IO) {
            runCatching {
                if (syncQueue.pendingCount() > 0) syncQueue.drain(spreadsheetGateways, binaryGateways)
            }.onFailure { Log.w(TAG_UPLOAD, "reconcile: pre-drain failed", it) }

            val stillQueued = runCatching { syncQueueStore.load() }.getOrDefault(emptyList())
                .filter { it.kind == OpKind.SHEET_ROW }
                .map { it.provider }
                .toSet()
            val clouds = linked
                .filter { it !in stillQueued }
                .mapNotNull { spreadsheetGateways[it] as? CloudLedger }
            if (stillQueued.isNotEmpty()) Log.d(TAG_UPLOAD, "reconcile: skipping $stillQueued (rows still queued)")
            if (clouds.isEmpty()) return@withContext

            runCatching {
                val local = LedgerReader(csvSink.ledgerFile()).readRecords()
                val report = LedgerReconciler().reconcile(local, clouds)
                Log.d(
                    TAG_UPLOAD,
                    "reconcile: ${local.size} local row(s); appended=${report.appended} failed=${report.failed}",
                )
            }.onFailure { Log.w(TAG_UPLOAD, "reconcile failed", it) }
        }
    }

    /** Regenerates `Documents/Shreddro Transactions.xlsx` from the CSV. Logged, never thrown. */
    suspend fun refreshLocalLedger() {
        runCatching { localXlsx.write(LedgerReader(csvSink.ledgerFile()).readRecords()) }
            .onFailure { Log.w(TAG_UPLOAD, "local xlsx refresh failed", it) }
    }

    /** The local .xlsx stands in for the cloud ledgers only while no account is linked. */
    suspend fun refreshLocalLedgerIfUnlinked() {
        if (auth.linkedProviders().isEmpty()) refreshLocalLedger()
    }

    /**
     * One-time fix-ups of persisted scan state across builds.
     *
     * Schema 2: earlier builds only scanned Camera/Screenshots buckets and
     * stamped the watermark to "now" after every scan, so slips saved by
     * banking apps (Pictures/K PLUS, …) that pre-date that stamp were never
     * discoverable. Reset the watermark once so the first scan on this build
     * covers them; the SHA-256 registry keeps already-handled images deduped.
     */
    private fun migrateScanState() {
        if (prefs.getInt(KEY_SCAN_SCHEMA, 1) < SCAN_SCHEMA) {
            resetScanWatermark()
            prefs.edit().putInt(KEY_SCAN_SCHEMA, SCAN_SCHEMA).apply()
        }
    }

    private fun isOnline(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }

    private companion object {
        const val KEY_SCAN_SCHEMA = "scan_schema"
        const val SCAN_SCHEMA = 2
        const val TAG_UPLOAD = "Shreddro.Upload"
    }
}

/**
 * Keeps [AndroidCsvSink] a pure CSV sink: [afterAppend] runs once the row is
 * durably on disk (used to regenerate the local .xlsx mirror). It must never
 * throw — the CSV row is already safe and authorizes the purge on its own.
 */
private class MirroringLedgerSink(
    private val delegate: LedgerSink,
    private val afterAppend: suspend () -> Unit,
) : LedgerSink {
    override suspend fun append(slip: TransactionSlip, sourceMediaId: String, imageFileName: String) {
        delegate.append(slip, sourceMediaId, imageFileName)
        afterAppend()
    }
}
