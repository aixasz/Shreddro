package com.shreddro.app

import android.app.Application
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.shreddro.app.auth.AppAuthManager
import com.shreddro.app.data.AndroidCsvSink
import com.shreddro.app.data.AppSettings
import com.shreddro.app.data.FileArchiveReader
import com.shreddro.app.data.FileProcessedStore
import com.shreddro.app.data.FileReviewStore
import com.shreddro.app.data.FileSyncQueueStore
import com.shreddro.app.net.AppsScriptSheetGateway
import com.shreddro.app.net.Clients
import com.shreddro.app.net.CompressedUploadGateway
import com.shreddro.app.net.DriveBinaryGateway
import com.shreddro.app.net.GraphExcelGateway
import com.shreddro.app.net.OneDriveBinaryGateway
import com.shreddro.app.ocr.MlKitSlipValidator
import com.shreddro.app.ocr.OfflineSlipParser
import com.shreddro.app.storage.StorageCoordinator
import com.shreddro.app.work.SyncDrainWorker
import com.shreddro.core.gateway.BinaryStorageGateway
import com.shreddro.core.gateway.SpreadsheetGateway
import com.shreddro.core.gateway.SyncStateProvider
import com.shreddro.core.model.CloudProvider
import com.shreddro.core.model.SyncState
import com.shreddro.core.pipeline.SlipPipeline
import com.shreddro.core.queue.SyncQueue
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
        syncQueue = SyncQueue(FileSyncQueueStore(this), FileArchiveReader())
        registry = ProcessedRegistry(FileProcessedStore(this))
        reviewQueue = ReviewQueue(FileReviewStore(this))
        pendingPurge = com.shreddro.app.data.PendingPurgeStore(this)
        csvSink = AndroidCsvSink(this)

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

        spreadsheetGateways = buildMap {
            if (settings.appsScriptUrl.isNotBlank()) {
                put(
                    CloudProvider.GOOGLE,
                    AppsScriptSheetGateway(
                        Clients.appsScriptApi,
                        settings.appsScriptUrl,
                        settings.appsScriptSecret,
                        onSheetUrl = { settings.googleSheetUrl = it },
                    ),
                )
            }
            if (settings.msWorkbookItemId.isNotBlank()) {
                put(
                    CloudProvider.MICROSOFT,
                    GraphExcelGateway(
                        Clients.graphApi, auth, settings.msWorkbookItemId,
                        onWorkbookUrl = { settings.excelWorkbookUrl = it },
                    ),
                )
            }
        }
        // Cloud copies go up as downsized JPEGs (toggle in Settings); the local
        // archive the purge invariant hashes against is never touched.
        val compressed = { settings.compressUploads }
        binaryGateways = mapOf(
            CloudProvider.GOOGLE to CompressedUploadGateway(
                DriveBinaryGateway(
                    auth, Clients.okHttp,
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
            ledger = csvSink,
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
    }
}
