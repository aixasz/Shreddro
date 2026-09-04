package com.shreddro.core.csv

import com.shreddro.core.ledger.LedgerRecord

/**
 * Reads the local CSV ledger back into [LedgerRecord]s. Inverse of
 * [CsvFormatter]: same RFC 4180 quoting rules, and tolerant of both the v1
 * 8-column layout (no `image_file`) and the v2 9-column layout.
 */
object CsvLedgerParser {

    /** Number of columns written by v1 builds (up to and including source_media_id). */
    private const val LEGACY_COLUMNS = 8

    /** Column count in the current layout ([CsvFormatter.HEADERS]). */
    private val CURRENT_COLUMNS = CsvFormatter.HEADERS.size

    /**
     * Splits one physical CSV line into fields, honouring RFC 4180 quoting
     * (`"a, b"` stays one field, `""` inside quotes is a literal quote).
     * A trailing `\r` is dropped so CRLF files parse like LF files.
     */
    fun parseLine(line: String): List<String> {
        val text = line.removeSuffix("\r")
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                inQuotes && c == '"' -> {
                    if (i + 1 < text.length && text[i + 1] == '"') {
                        current.append('"')
                        i++
                    } else {
                        inQuotes = false
                    }
                }
                inQuotes -> current.append(c)
                c == '"' -> inQuotes = true
                c == ',' -> {
                    fields += current.toString()
                    current.setLength(0)
                }
                else -> current.append(c)
            }
            i++
        }
        fields += current.toString()
        return fields
    }

    /**
     * Maps a parsed row to a [LedgerRecord]. Accepts the legacy 8-column row
     * (image_file = "") and the current 9-column row. Returns null for the
     * header line, wrong column counts or a non-numeric amount.
     */
    fun toRecord(fields: List<String>): LedgerRecord? {
        if (fields.size != LEGACY_COLUMNS && fields.size != CURRENT_COLUMNS) return null
        if (fields[0] == CsvFormatter.HEADERS[0]) return null
        val amount = fields[3].trim().toDoubleOrNull() ?: return null
        return LedgerRecord(
            loggedAtUtc = fields[0],
            bankName = fields[1],
            dateTime = fields[2],
            amount = amount,
            sender = fields[4],
            receiver = fields[5],
            referenceId = fields[6],
            imageFile = fields.getOrNull(8) ?: "",
        )
    }

    /** Convenience: whole ledger text -> records, skipping the header and any unreadable line. */
    fun parse(text: String): List<LedgerRecord> =
        text.lineSequence()
            .filter { it.isNotBlank() }
            .mapNotNull { toRecord(parseLine(it)) }
            .toList()
}
