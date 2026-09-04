package com.shreddro.app.data

import android.content.Context
import com.shreddro.core.csv.CsvFormatter
import com.shreddro.core.gateway.LedgerSink
import com.shreddro.core.model.TransactionSlip
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.time.Instant

/**
 * Thread-safe, crash-safe CSV ledger in the app's private sandbox:
 * `filesDir/ledger/transactions.csv`.
 *
 * - Serialized through a [Mutex] so concurrent pipeline batches never
 *   interleave partial lines.
 * - Appends are a single write + fsync of "line\n"; a crash can lose the last
 *   line but never corrupt earlier rows.
 * - UTF-8 with a BOM on file creation so Excel renders Thai text correctly
 *   when the user exports/opens the file directly.
 * - A file written by a v1 build (8 columns, no `image_file`) is migrated to
 *   the v2 layout once, on the first append: see [migrateLegacyLayout].
 */
class AndroidCsvSink(context: Context) : LedgerSink {

    private val file = File(File(context.filesDir, "ledger").apply { mkdirs() }, "transactions.csv")
    private val mutex = Mutex()

    /** Layout check runs once per process; guarded by [mutex]. */
    private var layoutChecked = false

    override suspend fun append(slip: TransactionSlip, sourceMediaId: String, imageFileName: String) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                migrateLegacyLayout()
                val isNew = !file.exists() || file.length() == 0L
                FileOutputStream(file, true).use { out ->
                    if (isNew) {
                        out.write(UTF8_BOM)
                        out.write((CsvFormatter.headerLine() + "\n").toByteArray(Charsets.UTF_8))
                    }
                    val line = CsvFormatter.toLine(slip, sourceMediaId, imageFileName, Instant.now().toString())
                    out.write((line + "\n").toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
            }
        }
    }

    fun ledgerFile(): File = file

    /**
     * v1 -> v2 layout, once. If the header line is the old 8-column header the
     * whole file is rewritten with the 9-column header and every row padded
     * with a trailing empty `image_file` field. Temp file + fsync + atomic
     * rename (same style as the state stores): a crash mid-way leaves the v1
     * file untouched and the check simply runs again on the next append.
     */
    private fun migrateLegacyLayout() {
        if (layoutChecked) return
        if (!file.exists() || file.length() == 0L) {
            layoutChecked = true
            return
        }
        val text = file.readText(Charsets.UTF_8)
        val hasBom = text.startsWith(BOM)
        val body = text.removePrefix(BOM)
        val header = body.lineSequence().firstOrNull()?.removeSuffix("\r") ?: ""
        if (header != LEGACY_HEADER) {
            layoutChecked = true
            return
        }

        val migrated = buildString(text.length + 64) {
            if (hasBom) append(BOM)
            append(CsvFormatter.headerLine()).append('\n')
            for (row in csvRecords(body.lineSequence().drop(1))) append(row).append(',').append('\n')
        }

        val tmp = File(file.parentFile, "${file.name}.tmp")
        FileOutputStream(tmp).use { out ->
            out.write(migrated.toByteArray(Charsets.UTF_8))
            out.fd.sync()
        }
        if (!tmp.renameTo(file)) {
            tmp.delete()
            throw IOException("Atomic rename failed for ${file.path}")
        }
        layoutChecked = true
    }

    /**
     * Groups physical lines into CSV records. A quoted field may legally span
     * lines ([CsvFormatter.escape] quotes embedded newlines), which shows up
     * as an odd number of quotes so far — keep accumulating until it is even.
     */
    private fun csvRecords(lines: Sequence<String>): List<String> {
        val records = mutableListOf<String>()
        val current = StringBuilder()
        var quotes = 0
        for (raw in lines) {
            val line = raw.removeSuffix("\r")
            if (current.isNotEmpty()) current.append('\n')
            current.append(line)
            quotes += line.count { it == '"' }
            if (quotes % 2 == 0) {
                if (current.isNotBlank()) records += current.toString()
                current.setLength(0)
                quotes = 0
            }
        }
        if (current.isNotBlank()) records += current.toString()
        return records
    }

    private companion object {
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
        val BOM = Char(0xFEFF).toString()

        /** Header written by v1 builds: everything up to and including source_media_id. */
        val LEGACY_HEADER = CsvFormatter.HEADERS.dropLast(1).joinToString(",")
    }
}
