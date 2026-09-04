package com.shreddro.core

import com.shreddro.core.csv.CsvFormatter
import com.shreddro.core.gateway.SlipParseException
import com.shreddro.core.parse.SlipJsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Golden fixtures transcribed from five REAL slips (owner-provided) covering
 * the user's three banks — Bangkok Bank, Krungthai NEXT, and KBank K+ —
 * across transfer, merchant-payment, and bill-payment layouts. The JSON here
 * is what a correctly-prompted Vision LLM should emit for each image; these
 * tests pin the parser + CSV tail against that reality:
 *  - mixed Thai/English names, masked account numbers
 *  - Buddhist-era dates printed in Thai ("30 ส.ค. 2569 - 14:16")
 *  - amounts with satang, and >1,000 THB (separator must NOT appear in JSON)
 *  - long numeric references and alphanumeric ones (mKTB…, …BQR…, …OFTKT…)
 */
class GoldenSlipSamplesTest {

    // ── Bangkok Bank: PromptPay transfer to another person (English UI) ──
    private val bblTransfer = """
        {
          "bank_name": "Bangkok Bank",
          "date_time": "29 Aug 26, 17:27",
          "amount": 1790.00,
          "sender": "นาย นัทธพงศ์ (086-8-xxx871)",
          "receiver": "DARAINTR INTR (060-2-xxx626, Siam Commercial Bank)",
          "reference_id": "2026082917275423003097308"
        }
    """.trimIndent()

    // ── Bangkok Bank: merchant QR payment to a company (Thai receiver) ──
    private val bblMerchant = """
        {
          "bank_name": "Bangkok Bank",
          "date_time": "03 Sep 26, 10:05",
          "amount": 115.00,
          "sender": "นาย นัทธพงศ์ (086-8-xxx871)",
          "receiver": "บริษัท ซีพีเอฟ เรสเทอรองท์ แอนด์ ฟู้ดเชน จำกัด",
          "reference_id": "2026090310054523002445308"
        }
    """.trimIndent()

    // ── Krungthai: merchant bill payment, Buddhist-era Thai date ──
    private val ktbIceStation = """
        {
          "bank_name": "Krungthai",
          "date_time": "30 ส.ค. 2569 - 14:16",
          "amount": 25.00,
          "sender": "นายนัทธพงศ์ น***",
          "receiver": "Ice Station (010753600031501)",
          "reference_id": "C20260830624214065059"
        }
    """.trimIndent()

    // ── Krungthai: utility bill (Metropolitan Waterworks), satang amount ──
    private val ktbWaterBill = """
        {
          "bank_name": "Krungthai",
          "date_time": "30 ส.ค. 2569 - 13:44",
          "amount": 115.79,
          "sender": "นายนัทธพงศ์ น***",
          "receiver": "การประปานครหลวง (กปน.) (7337)",
          "reference_id": "mKTB5344216038"
        }
    """.trimIndent()

    // ── KBank K+: payment to a shop, เลขที่รายการ reference ──
    private val kbankFellow = """
        {
          "bank_name": "KBank",
          "date_time": "30 ส.ค. 69 11:12 น.",
          "amount": 230.00,
          "sender": "นาย นัทธพงศ์ น (ธ.กสิกรไทย xxx-x-x5474-x)",
          "receiver": "fellow and friends (น.ส. ธัญชนก อินทร์ใจเอื้อ)",
          "reference_id": "016242111202BQR03170"
        }
    """.trimIndent()

    @Test
    fun `bangkok bank transfer parses with separator-free thousands amount`() {
        val slip = SlipJsonParser.parse(bblTransfer)
        assertEquals("Bangkok Bank", slip.bankName)
        assertEquals(1790.00, slip.amount)
        assertEquals("2026082917275423003097308", slip.referenceId)
    }

    @Test
    fun `bangkok bank merchant payment parses thai company receiver`() {
        val slip = SlipJsonParser.parse(bblMerchant)
        assertEquals("บริษัท ซีพีเอฟ เรสเทอรองท์ แอนด์ ฟู้ดเชน จำกัด", slip.receiver)
        assertEquals(115.00, slip.amount)
    }

    @Test
    fun `krungthai bill payments keep buddhist-era dates as printed`() {
        assertEquals("30 ส.ค. 2569 - 14:16", SlipJsonParser.parse(ktbIceStation).dateTime)
        assertEquals("30 ส.ค. 2569 - 13:44", SlipJsonParser.parse(ktbWaterBill).dateTime)
    }

    @Test
    fun `satang amounts survive to csv without locale drift`() {
        val slip = SlipJsonParser.parse(ktbWaterBill)
        assertEquals(115.79, slip.amount)
        val line = CsvFormatter.toLine(slip, "m", "water.jpg", "2026-08-30T06:44:00Z")
        assertTrue("115.79" in line)
    }

    @Test
    fun `kbank slip parses mixed thai-english parties and alnum reference`() {
        val slip = SlipJsonParser.parse(kbankFellow)
        assertEquals("KBank", slip.bankName)
        assertEquals("016242111202BQR03170", slip.referenceId)
        assertEquals(230.00, slip.amount)
    }

    @Test
    fun `all five real banks produce sheet-and-folder-safe bank keys`() {
        val keys = listOf(bblTransfer, bblMerchant, ktbIceStation, ktbWaterBill, kbankFellow)
            .map { SlipJsonParser.parse(it).bankKey }
        assertEquals(listOf("Bangkok Bank", "Bangkok Bank", "Krungthai", "Krungthai", "KBank"), keys)
    }

    @Test
    fun `thai names with parentheses and commas stay intact through csv`() {
        val slip = SlipJsonParser.parse(kbankFellow)
        val line = CsvFormatter.toLine(slip, "media-9", "fellow.jpg", "2026-08-30T04:12:00Z")
        assertTrue("fellow and friends (น.ส. ธัญชนก อินทร์ใจเอื้อ)" in line)
    }

    @Test
    fun `model emitting a thousands separator is rejected not silently mangled`() {
        // "amount": 1,790.00 is invalid JSON — must raise for the repair retry,
        // never parse as 1.0 and log a wrong amount.
        val bad = bblTransfer.replace("1790.00", "1,790.00")
        assertFailsWith<SlipParseException> { SlipJsonParser.parse(bad) }
    }
}
