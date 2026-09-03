package com.shreddro.app.data

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
)

/** Read-only view over AndroidCsvSink's file for the Ledger tab and Home stats. */
class LedgerReader(private val file: File) {

    suspend fun readAll(): List<LedgerEntry> = withContext(Dispatchers.IO) {
        if (!file.exists()) return@withContext emptyList()
        file.readText(Charsets.UTF_8)
            .removePrefix("﻿")
            .lineSequence()
            .drop(1) // header
            .filter { it.isNotBlank() }
            .mapNotNull { line ->
                val f = parseCsvLine(line)
                if (f.size < 8) null else LedgerEntry(
                    loggedAtUtc = f[0],
                    bankName = f[1],
                    dateTime = f[2],
                    amount = f[3].toDoubleOrNull() ?: 0.0,
                    sender = f[4],
                    receiver = f[5],
                    referenceId = f[6],
                )
            }
            .toList()
            .reversed() // newest first
    }

    /** Entries whose logged_at falls in the given "yyyy-MM" UTC month. */
    fun monthOf(entries: List<LedgerEntry>, yearMonth: String): List<LedgerEntry> =
        entries.filter { it.loggedAtUtc.startsWith(yearMonth) }

    /** Minimal RFC 4180 field split (quotes, escaped quotes, commas). */
    private fun parseCsvLine(line: String): List<String> {
        val fields = mutableListOf<String>()
        val sb = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < line.length) {
            val c = line[i]
            when {
                inQuotes && c == '"' && i + 1 < line.length && line[i + 1] == '"' -> {
                    sb.append('"'); i++
                }
                c == '"' -> inQuotes = !inQuotes
                c == ',' && !inQuotes -> { fields += sb.toString(); sb.clear() }
                else -> sb.append(c)
            }
            i++
        }
        fields += sb.toString()
        return fields
    }
}
