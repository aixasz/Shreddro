package com.shreddro.core.csv

import com.shreddro.core.model.TransactionSlip

/**
 * RFC 4180 CSV formatting for the local ledger. Pure functions — the actual
 * file I/O lives in the platform adapter ([com.shreddro.core.gateway.LedgerSink] impl).
 *
 * Column layout (v2). `image_file` is the LAST column so files written by
 * v1 builds (8 columns, no image) stay readable: see [CsvLedgerParser].
 */
object CsvFormatter {

    val HEADERS = listOf(
        "logged_at_utc", "bank_name", "date_time", "amount",
        "sender", "receiver", "reference_id", "source_media_id", "image_file",
    )

    fun headerLine(): String = HEADERS.joinToString(",")

    fun toLine(
        slip: TransactionSlip,
        sourceMediaId: String,
        imageFileName: String,
        loggedAtUtcIso: String,
    ): String =
        listOf(
            loggedAtUtcIso,
            slip.bankName,
            slip.dateTime,
            formatAmount(slip.amount),
            slip.sender,
            slip.receiver,
            slip.referenceId,
            sourceMediaId,
            imageFileName,
        ).joinToString(",") { escape(it) }

    /** Stable 2-decimal representation; avoids locale comma separators and sci-notation. */
    fun formatAmount(amount: Double): String {
        val cents = kotlin.math.round(amount * 100).toLong()
        val sign = if (cents < 0) "-" else ""
        val abs = kotlin.math.abs(cents)
        return "$sign${abs / 100}.${(abs % 100).toString().padStart(2, '0')}"
    }

    internal fun escape(field: String): String {
        val needsQuoting = field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }
        return if (needsQuoting) "\"${field.replace("\"", "\"\"")}\"" else field
    }
}
