package com.shreddro.app.data

import com.shreddro.core.csv.CsvLedgerParser
import com.shreddro.core.ledger.LedgerRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** One row of the local CSV ledger, for on-screen history and stats. */
data class LedgerEntry(
    val loggedAtUtc: String,
    val bankName: String,
    val dateTime: String,
    val amount: Double,
    val sender: String,
    val receiver: String,
    val referenceId: String,
    val imageFile: String = "",
) {
    fun toRecord(): LedgerRecord = LedgerRecord(
        loggedAtUtc = loggedAtUtc,
        bankName = bankName,
        dateTime = dateTime,
        amount = amount,
        sender = sender,
        receiver = receiver,
        referenceId = referenceId,
        imageFile = imageFile,
    )
}

fun LedgerRecord.toEntry(): LedgerEntry = LedgerEntry(
    loggedAtUtc = loggedAtUtc,
    bankName = bankName,
    dateTime = dateTime,
    amount = amount,
    sender = sender,
    receiver = receiver,
    referenceId = referenceId,
    imageFile = imageFile,
)

/**
 * Read-only view over AndroidCsvSink's file for the Ledger tab, Home stats,
 * cloud reconciliation and the local Excel mirror. Parsing is core's
 * [CsvLedgerParser], so v1 (8-column) and v2 (9-column) files both read.
 */
class LedgerReader(private val file: File) {

    /** Every row in file (chronological) order; header and unreadable lines skipped. */
    suspend fun readRecords(): List<LedgerRecord> = withContext(Dispatchers.IO) {
        if (!file.exists()) emptyList()
        else CsvLedgerParser.parse(file.readText(Charsets.UTF_8).removePrefix(Char(0xFEFF).toString()))
    }

    /** Newest first, for on-screen lists. */
    suspend fun readAll(): List<LedgerEntry> = readRecords().map { it.toEntry() }.reversed()

    /** Entries whose logged_at falls in the given "yyyy-MM" UTC month. */
    fun monthOf(entries: List<LedgerEntry>, yearMonth: String): List<LedgerEntry> =
        entries.filter { it.loggedAtUtc.startsWith(yearMonth) }
}
