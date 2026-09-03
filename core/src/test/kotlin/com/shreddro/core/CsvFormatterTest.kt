package com.shreddro.core

import com.shreddro.core.csv.CsvFormatter
import com.shreddro.core.model.TransactionSlip
import kotlin.test.Test
import kotlin.test.assertEquals

class CsvFormatterTest {

    private val slip = TransactionSlip(
        bankName = "Krungthai, NEXT",
        dateTime = "2026-09-03 14:22",
        amount = 1234.5,
        sender = "สมชาย \"ชาย\" ใจดี",
        receiver = "SOMSRI",
        referenceId = "REF123",
    )

    @Test
    fun `escapes commas and quotes per rfc4180`() {
        val line = CsvFormatter.toLine(slip, "media-1", "2026-09-03T08:00:00Z")
        assertEquals(
            "2026-09-03T08:00:00Z,\"Krungthai, NEXT\",2026-09-03 14:22,1234.50," +
                "\"สมชาย \"\"ชาย\"\" ใจดี\",SOMSRI,REF123,media-1",
            line,
        )
    }

    @Test
    fun `amount always has two decimals without locale separators`() {
        assertEquals("0.05", CsvFormatter.formatAmount(0.05))
        assertEquals("1000000.00", CsvFormatter.formatAmount(1_000_000.0))
        assertEquals("2.50", CsvFormatter.formatAmount(2.5))
        assertEquals("-15.75", CsvFormatter.formatAmount(-15.75))
    }

    @Test
    fun `header matches column order`() {
        assertEquals(
            "logged_at_utc,bank_name,date_time,amount,sender,receiver,reference_id,source_media_id",
            CsvFormatter.headerLine(),
        )
    }
}
