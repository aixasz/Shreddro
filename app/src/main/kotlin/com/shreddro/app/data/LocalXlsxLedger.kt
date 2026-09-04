package com.shreddro.app.data

import android.content.Context
import android.os.Environment
import com.shreddro.core.csv.CsvFormatter
import com.shreddro.core.ledger.LedgerRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * `Documents/Shreddro Transactions.xlsx` in the app's external-files area —
 * the spreadsheet a user WITHOUT a linked cloud account gets. Same 8 columns
 * as the Google Sheet / Excel Online ledgers, one sheet named "Slips",
 * regenerated in full from the CSV (the source of truth) so it can never
 * drift from it.
 *
 * Dependency-free minimal OOXML: five parts in a zip, inline strings for text
 * cells (no shared-strings table) and plain numeric cells for the amount.
 * Written atomically (temp file + fsync + rename) so a reader never sees a
 * half-written workbook.
 */
class LocalXlsxLedger(context: Context) {

    val file: File = File(
        context.getExternalFilesDir(Environment.DIRECTORY_DOCUMENTS)
            ?: File(context.filesDir, Environment.DIRECTORY_DOCUMENTS),
        FILE_NAME,
    )
    private val mutex = Mutex()

    suspend fun write(records: List<LedgerRecord>) {
        mutex.withLock {
            withContext(Dispatchers.IO) {
                file.parentFile?.mkdirs()
                val tmp = File(file.parentFile, "${file.name}.tmp")
                FileOutputStream(tmp).use { fos ->
                    val zip = ZipOutputStream(BufferedOutputStream(fos))
                    zip.part("[Content_Types].xml", CONTENT_TYPES)
                    zip.part("_rels/.rels", ROOT_RELS)
                    zip.part("xl/workbook.xml", WORKBOOK)
                    zip.part("xl/_rels/workbook.xml.rels", WORKBOOK_RELS)
                    zip.part("xl/worksheets/sheet1.xml", sheetXml(records))
                    zip.finish()
                    zip.flush()
                    fos.fd.sync()
                    zip.close()
                }
                if (!tmp.renameTo(file)) {
                    tmp.delete()
                    throw IOException("Atomic rename failed for ${file.path}")
                }
            }
        }
    }

    private fun ZipOutputStream.part(name: String, xml: String) {
        putNextEntry(ZipEntry(name))
        write(xml.toByteArray(Charsets.UTF_8))
        closeEntry()
    }

    private fun sheetXml(records: List<LedgerRecord>): String = buildString {
        append(XML_DECL)
        append("<worksheet xmlns=\"$NS_MAIN\">")
        append("<cols><col min=\"1\" max=\"${LedgerColumns.HEADERS.size}\" width=\"22\" customWidth=\"1\"/></cols>")
        append("<sheetData>")
        append("<row r=\"1\">")
        LedgerColumns.HEADERS.forEachIndexed { column, header -> textCell(column, 1, header) }
        append("</row>")
        records.forEachIndexed { index, r ->
            val rowNumber = index + 2
            append("<row r=\"$rowNumber\">")
            textCell(0, rowNumber, r.loggedAtUtc)
            textCell(1, rowNumber, r.bankName)
            textCell(2, rowNumber, r.dateTime)
            numberCell(3, rowNumber, r.amount)
            textCell(4, rowNumber, r.sender)
            textCell(5, rowNumber, r.receiver)
            textCell(6, rowNumber, r.referenceId)
            textCell(7, rowNumber, r.imageFile)
            append("</row>")
        }
        append("</sheetData>")
        append("</worksheet>")
    }

    private fun StringBuilder.textCell(column: Int, rowNumber: Int, text: String) {
        append("<c r=\"").append(cellRef(column, rowNumber)).append("\" t=\"inlineStr\"><is><t")
        if (text.isNotEmpty() && (text.first().isWhitespace() || text.last().isWhitespace())) {
            append(" xml:space=\"preserve\"")
        }
        append('>').append(escapeXml(text)).append("</t></is></c>")
    }

    private fun StringBuilder.numberCell(column: Int, rowNumber: Int, value: Double) {
        append("<c r=\"").append(cellRef(column, rowNumber)).append("\"><v>")
        append(CsvFormatter.formatAmount(value))
        append("</v></c>")
    }

    private fun cellRef(column: Int, rowNumber: Int): String = "${'A' + column}$rowNumber"

    /** XML 1.0 escaping; control characters XML cannot carry are dropped. */
    private fun escapeXml(text: String): String = buildString(text.length + 8) {
        for (c in text) {
            when (c) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\t', '\n', '\r' -> append(c)
                else -> if (c.code >= 0x20 && c.code != 0xFFFE && c.code != 0xFFFF) append(c)
            }
        }
    }

    companion object {
        const val FILE_NAME = "Shreddro Transactions.xlsx"
        const val MIME = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"

        /** Same columns, same order, as the cloud ledgers. */

        private const val XML_DECL = "<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"yes\"?>"
        private const val NS_MAIN = "http://schemas.openxmlformats.org/spreadsheetml/2006/main"
        private const val NS_REL_DOC = "http://schemas.openxmlformats.org/officeDocument/2006/relationships"
        private const val NS_PKG_REL = "http://schemas.openxmlformats.org/package/2006/relationships"

        private const val CONTENT_TYPES = XML_DECL +
            "<Types xmlns=\"http://schemas.openxmlformats.org/package/2006/content-types\">" +
            "<Default Extension=\"rels\" ContentType=\"application/vnd.openxmlformats-package.relationships+xml\"/>" +
            "<Default Extension=\"xml\" ContentType=\"application/xml\"/>" +
            "<Override PartName=\"/xl/workbook.xml\" " +
            "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml\"/>" +
            "<Override PartName=\"/xl/worksheets/sheet1.xml\" " +
            "ContentType=\"application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml\"/>" +
            "</Types>"

        private const val ROOT_RELS = XML_DECL +
            "<Relationships xmlns=\"$NS_PKG_REL\">" +
            "<Relationship Id=\"rId1\" Type=\"$NS_REL_DOC/officeDocument\" Target=\"xl/workbook.xml\"/>" +
            "</Relationships>"

        private const val WORKBOOK = XML_DECL +
            "<workbook xmlns=\"$NS_MAIN\" xmlns:r=\"$NS_REL_DOC\">" +
            "<sheets><sheet name=\"Slips\" sheetId=\"1\" r:id=\"rId1\"/></sheets>" +
            "</workbook>"

        private const val WORKBOOK_RELS = XML_DECL +
            "<Relationships xmlns=\"$NS_PKG_REL\">" +
            "<Relationship Id=\"rId1\" Type=\"$NS_REL_DOC/worksheet\" Target=\"worksheets/sheet1.xml\"/>" +
            "</Relationships>"
    }
}
