package com.shreddro.core

import com.shreddro.core.csv.CsvFormatter
import com.shreddro.core.csv.CsvLedgerParser
import com.shreddro.core.ledger.LedgerRecord
import com.shreddro.core.model.TransactionSlip
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CsvLedgerParserTest {

    private val slip = TransactionSlip(
        bankName = "Krungthai, NEXT",
        dateTime = "2026-09-03 14:22",
        amount = 1234.5,
        sender = "สมชาย \"ชาย\" ใจดี",
        receiver = "SOMSRI",
        referenceId = "REF123",
    )

    @Test
    fun `parseLine honours rfc4180 quoting`() {
        assertEquals(
            listOf("a", "b, c", "say \"hi\"", "", "d"),
            CsvLedgerParser.parseLine("a,\"b, c\",\"say \"\"hi\"\"\",,d"),
        )
        assertEquals(listOf("x", "y"), CsvLedgerParser.parseLine("x,y\r"))
        assertEquals(listOf(""), CsvLedgerParser.parseLine(""))
    }

    @Test
    fun `new nine-column row round-trips to a record with image file`() {
        val line = CsvFormatter.toLine(slip, "content://media/1", "slip, \"one\".jpg", "2026-09-03T08:00:00Z")
        val record = CsvLedgerParser.toRecord(CsvLedgerParser.parseLine(line))
        assertEquals(
            LedgerRecord(
                loggedAtUtc = "2026-09-03T08:00:00Z",
                bankName = "Krungthai, NEXT",
                dateTime = "2026-09-03 14:22",
                amount = 1234.5,
                sender = "สมชาย \"ชาย\" ใจดี",
                receiver = "SOMSRI",
                referenceId = "REF123",
                imageFile = "slip, \"one\".jpg",
            ),
            record,
        )
        assertEquals(slip, record!!.toSlip())
    }

    @Test
    fun `legacy eight-column row parses with empty image file`() {
        val legacy = "2026-09-03T08:00:00Z,\"Krungthai, NEXT\",2026-09-03 14:22,1234.50," +
            "\"สมชาย \"\"ชาย\"\" ใจดี\",SOMSRI,REF123,media-1"
        val record = CsvLedgerParser.toRecord(CsvLedgerParser.parseLine(legacy))
        assertEquals("", record!!.imageFile)
        assertEquals(slip, record.toSlip())
        assertEquals("REF123", record.key)
    }

    @Test
    fun `header, malformed and non-numeric rows are rejected`() {
        assertNull(CsvLedgerParser.toRecord(CsvLedgerParser.parseLine(CsvFormatter.headerLine())))
        val legacyHeader = "logged_at_utc,bank_name,date_time,amount,sender,receiver,reference_id,source_media_id"
        assertNull(CsvLedgerParser.toRecord(CsvLedgerParser.parseLine(legacyHeader)))
        assertNull(CsvLedgerParser.toRecord(listOf("a", "b", "c")))
        assertNull(CsvLedgerParser.toRecord(listOf("t", "SCB", "d", "abc", "s", "r", "ref", "m", "img")))
    }

    @Test
    fun `parse reads a mixed legacy and new ledger file skipping the header`() {
        val text = buildString {
            appendLine("logged_at_utc,bank_name,date_time,amount,sender,receiver,reference_id,source_media_id")
            appendLine("2026-09-01T00:00:00Z,KBank,2026-09-01 09:00,10.00,A,B,,media-1")
            appendLine(CsvFormatter.toLine(slip, "media-2", "IMG_2.jpg", "2026-09-02T00:00:00Z"))
            appendLine()
        }
        val records = CsvLedgerParser.parse(text)
        assertEquals(2, records.size)
        assertEquals("KBank|2026-09-01 09:00|10.00|", records[0].key)
        assertEquals("IMG_2.jpg", records[1].imageFile)
    }
}
