package com.shreddro.core.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * The canonical data contract for a parsed Thai bank slip.
 * Field names/serial names match the Vision-LLM output contract EXACTLY —
 * do not rename without versioning the prompt, the Apps Script gateway,
 * and the Graph table schema together.
 */
@Serializable
data class TransactionSlip(
    @SerialName("bank_name") val bankName: String,
    @SerialName("date_time") val dateTime: String,
    @SerialName("amount") val amount: Double,
    @SerialName("sender") val sender: String,
    @SerialName("receiver") val receiver: String,
    @SerialName("reference_id") val referenceId: String,
) {
    /** Sanitized bank name safe for sheet-tab titles, folder names and table names. */
    val bankKey: String get() = bankKeyOf(bankName)

    companion object {
        /** Same sanitizing as [bankKey], for callers that only hold a bank name (e.g. ledger rows). */
        fun bankKeyOf(bankName: String): String = bankName.trim()
            .replace(Regex("[\\\\/\\[\\]*?:'\"<>|]"), "")
            .take(80)
            .ifBlank { "Unknown" }
    }
}
