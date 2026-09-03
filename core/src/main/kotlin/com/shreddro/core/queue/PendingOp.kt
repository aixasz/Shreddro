package com.shreddro.core.queue

import com.shreddro.core.model.CloudProvider
import com.shreddro.core.model.TransactionSlip
import kotlinx.serialization.Serializable

enum class OpKind {
    /** Append the transaction row to the provider's spreadsheet engine. */
    SHEET_ROW,

    /** Upload the raw slip image (read back from the private archive). */
    BINARY,
}

/**
 * One durable unit of deferred cloud work. Created when a sync target is
 * offline or fails; drained later by the platform's background worker.
 */
@Serializable
data class PendingOp(
    val id: String,
    val provider: CloudProvider,
    val kind: OpKind,
    val slip: TransactionSlip,
    val sourceMediaId: String,
    val fileName: String,
    /** Private-archive path of the image; null for SHEET_ROW ops. */
    val archivePath: String? = null,
    val attempts: Int = 0,
    val createdAtEpochMs: Long,
)
