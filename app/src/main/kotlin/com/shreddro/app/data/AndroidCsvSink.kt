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
 */
class AndroidCsvSink(context: Context) : LedgerSink {

    private val file = File(File(context.filesDir, "ledger").apply { mkdirs() }, "transactions.csv")
    private val mutex = Mutex()

    override suspend fun append(slip: TransactionSlip, sourceMediaId: String) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                val isNew = !file.exists() || file.length() == 0L
                FileOutputStream(file, true).use { out ->
                    if (isNew) {
                        out.write(UTF8_BOM)
                        out.write((CsvFormatter.headerLine() + "\n").toByteArray(Charsets.UTF_8))
                    }
                    val line = CsvFormatter.toLine(slip, sourceMediaId, Instant.now().toString())
                    out.write((line + "\n").toByteArray(Charsets.UTF_8))
                    out.fd.sync()
                }
            }
        }
    }

    fun ledgerFile(): File = file

    private companion object {
        val UTF8_BOM = byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte())
    }
}
