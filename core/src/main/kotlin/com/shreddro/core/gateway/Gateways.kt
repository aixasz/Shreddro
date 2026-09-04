package com.shreddro.core.gateway

import com.shreddro.core.model.CloudProvider
import com.shreddro.core.model.TransactionSlip

/**
 * Hexagonal ports. :core owns these interfaces; the platform shell (:app on
 * Android, the iOS app in Phase 3) supplies the adapters. Nothing in :core
 * may import platform APIs.
 */

/** A candidate image discovered in the platform gallery. */
data class SlipCandidate(
    /** Platform-opaque identifier (Android: content:// URI string; iOS: PHAsset localIdentifier). */
    val mediaId: String,
    val displayName: String,
    val sha256: String,
    val bytes: ByteArray,
) {
    override fun equals(other: Any?) = other is SlipCandidate && other.sha256 == sha256
    override fun hashCode() = sha256.hashCode()
}

/** Local, on-device gate: cheap check for Thai text + bank QR before any cloud call. */
interface SlipValidator {
    suspend fun looksLikeBankSlip(candidate: SlipCandidate): Boolean
}

/** Vision LLM parser: image bytes -> structured contract. */
interface SlipParser {
    /** @throws SlipParseException when the model output cannot satisfy the contract. */
    suspend fun parse(candidate: SlipCandidate): TransactionSlip
}

class SlipParseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Appends one transaction row to a cloud spreadsheet engine, partitioned per
 * bank. [imageFileName] is the name the slip image carries in cloud storage
 * (see [BinaryStorageGateway.cloudFileName]) so every row maps back to its
 * image.
 */
interface SpreadsheetGateway {
    val provider: CloudProvider
    suspend fun appendRow(slip: TransactionSlip, imageFileName: String)
}

/** Uploads the raw slip binary to cloud object storage under a per-bank folder. */
interface BinaryStorageGateway {
    val provider: CloudProvider
    suspend fun upload(bytes: ByteArray, fileName: String, bankKey: String)

    /**
     * Name the image will have in cloud storage for a given original name.
     * Deterministic and side-effect free so the spreadsheet row can cite it
     * before (or without) the upload happening; adapters that transcode
     * (e.g. PNG -> JPEG) override it.
     */
    fun cloudFileName(originalName: String): String = originalName
}

/**
 * Durable local ledger (CSV in Phase 1). MUST be crash-safe and thread-safe.
 * [imageFileName] is the slip image's file name (the gallery display name)
 * so local rows can be reconciled against cloud rows by image, exactly like
 * [SpreadsheetGateway.appendRow].
 */
interface LedgerSink {
    suspend fun append(slip: TransactionSlip, sourceMediaId: String, imageFileName: String)
}

/**
 * Platform media vault: archive copy + gallery purge.
 * Android: app-scoped dir + .nomedia + MediaStore.createDeleteRequest.
 * iOS:     App Sandbox Documents + PHAssetChangeRequest.deleteAssets.
 */
interface MediaVault {
    /** Copies bytes into the private archive, verifies integrity, returns archive path. */
    suspend fun archive(candidate: SlipCandidate, bankKey: String): String

    /**
     * Requests platform deletion of the originals. May suspend on a user-consent
     * dialog; returns the media ids actually purged.
     */
    suspend fun requestPurge(mediaIds: List<String>): List<String>
}

/** Connectivity + auth snapshot supplied by the shell. */
interface SyncStateProvider {
    fun current(): com.shreddro.core.model.SyncState
}
