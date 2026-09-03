package com.shreddro.core

import com.shreddro.core.gateway.SlipParseException
import com.shreddro.core.parse.SlipJsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class SlipJsonParserTest {

    private val valid = """
        {
          "bank_name": "KBank",
          "date_time": "2026-09-03 14:22:05",
          "amount": 1250.50,
          "sender": "นายสมชาย ใจดี",
          "receiver": "MS. SOMSRI R.",
          "reference_id": "015163009522TFT00999"
        }
    """.trimIndent()

    @Test
    fun `parses clean contract json`() {
        val slip = SlipJsonParser.parse(valid)
        assertEquals("KBank", slip.bankName)
        assertEquals(1250.50, slip.amount)
        assertEquals("015163009522TFT00999", slip.referenceId)
    }

    @Test
    fun `parses json wrapped in markdown fences and prose`() {
        val raw = "Here is the extracted data:\n```json\n$valid\n```\nDone."
        val slip = SlipJsonParser.parse(raw)
        assertEquals("นายสมชาย ใจดี", slip.sender)
    }

    @Test
    fun `handles braces inside string values`() {
        val tricky = valid.replace("MS. SOMSRI R.", "MS. {SOMSRI} R.")
        assertEquals("MS. {SOMSRI} R.", SlipJsonParser.parse(tricky).receiver)
    }

    @Test
    fun `rejects missing fields`() {
        assertFailsWith<SlipParseException> {
            SlipJsonParser.parse("""{"bank_name":"SCB","amount":10.0}""")
        }
    }

    @Test
    fun `rejects negative amount`() {
        assertFailsWith<SlipParseException> {
            SlipJsonParser.parse(valid.replace("1250.50", "-5.0"))
        }
    }

    @Test
    fun `rejects output with no json at all`() {
        assertFailsWith<SlipParseException> {
            SlipJsonParser.parse("I cannot read this image.")
        }
    }
}
