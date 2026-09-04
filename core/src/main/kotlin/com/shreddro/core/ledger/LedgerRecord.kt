package com.shreddro.core.ledger

import com.shreddro.core.model.TransactionSlip
import java.util.Locale

/**
 * One logged transaction as it appears in a ledger — local CSV or a cloud
 * spreadsheet. Shared shape so the two can be compared row-for-row.
 */
data class LedgerRecord(
    val loggedAtUtc: String,
    val bankName: String,
    val dateTime: String,
    val amount: Double,
    val sender: String,
    val receiver: String,
    val referenceId: String,
    val imageFile: String = "",
) {
    /** Dedup key across local and cloud ledgers: reference id when present, else bank|dateTime|amount|imageFile. */
    val key: String
        get() {
            val ref = referenceId.trim()
            if (ref.isNotBlank()) return ref
            val amountText = "%.2f".format(Locale.US, amount)
            return "${bankName.trim()}|${dateTime.trim()}|$amountText|${imageFile.trim()}"
        }

    fun toSlip(): TransactionSlip = TransactionSlip(
        bankName = bankName,
        dateTime = dateTime,
        amount = amount,
        sender = sender,
        receiver = receiver,
        referenceId = referenceId,
    )
}
