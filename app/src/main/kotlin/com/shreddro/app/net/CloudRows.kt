package com.shreddro.app.net

import com.shreddro.core.ledger.LedgerRecord
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

/**
 * Maps a row read back from a cloud ledger (Sheets `values` / Graph table
 * `values`) to a [LedgerRecord]. Column order is the shared A..H layout:
 * (by position — see data/LedgerColumns for the readable names) logged_at_utc, bank_name, date_time, amount, sender, receiver,
 * reference_id, image_file. Missing trailing cells read as "".
 */
internal object CloudRows {

    fun toRecord(cells: List<String>): LedgerRecord {
        fun col(i: Int) = cells.getOrNull(i) ?: ""
        return LedgerRecord(
            loggedAtUtc = col(0),
            bankName = col(1),
            dateTime = col(2),
            amount = col(3).trim().toDoubleOrNull() ?: 0.0,
            sender = col(4),
            receiver = col(5),
            referenceId = col(6),
            imageFile = col(7),
        )
    }

    /** Raw cell text: strings unquoted, numbers/booleans as their literal, null as "". */
    fun cellText(element: JsonElement): String = when (element) {
        is JsonNull -> ""
        is JsonPrimitive -> element.content
        else -> element.toString()
    }
}
