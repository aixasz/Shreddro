package com.shreddro.core

import com.shreddro.core.gateway.SlipParseException
import com.shreddro.core.parse.ExtractedSlipText
import com.shreddro.core.parse.ThaiSlipTemplateParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * The OFFLINE template parser against the owner's five REAL slips: each
 * fixture is a faithful top-to-bottom transcript of the actual image — the
 * text Tesseract sees on-device. This is the ground-truth acceptance test
 * for the 100%-OCR pipeline (no LLM, no network).
 */
class TemplateParserRealSlipTest {

    private val parser = ThaiSlipTemplateParser()

    private fun text(vararg lines: String, qr: List<String> = emptyList()) =
        ExtractedSlipText(lines.toList(), qr)

    // ── Bangkok Bank · P2P transfer (English UI, thousands separator) ──
    private val bblTransfer = text(
        "Bangkok Bank",
        "Transaction successful",
        "29 Aug 26, 17:27",
        "Amount",
        "1,790.00 THB",
        "From",
        "นาย นัทธพงศ์",
        "086-8-xxx871",
        "Bangkok Bank",
        "To",
        "DARAINTR INTR",
        "060-2-xxx626",
        "Siam Commercial Bank",
        "Fee",
        "0.00 THB",
        "Bank reference no.",
        "324518",
        "Transaction reference",
        "2026082917275423003097308",
        "Scan to verify",
    )

    // ── Bangkok Bank · merchant QR payment (Thai company receiver) ──
    private val bblMerchant = text(
        "Bangkok Bank",
        "Transaction successful",
        "03 Sep 26, 10:05",
        "Amount",
        "115.00 THB",
        "From",
        "นาย นัทธพงศ์",
        "086-8-xxx871",
        "Bangkok Bank",
        "To",
        "บริษัท ซีพีเอฟ เรสเทอรองท์ แอนด์",
        "ฟู้ดเชน จำกัด",
        "Service Code:BBL01QR",
        "MERCHANTNO.1",
        "000002206489445",
        "REF#2",
        "478583397OFTKT000000",
        "Refernce no. (Optional)",
        "47858339",
        "Fee",
        "0.00 THB",
        "Bank reference no.",
        "375655",
        "Transaction reference",
        "2026090310054523002445308",
        "Scan to verify",
    )

    // ── Krungthai · merchant bill payment (Buddhist-era date) ──
    private val ktbIceStation = text(
        "Krungthai",
        "กรุงไทย",
        "จ่ายบิลสำเร็จ",
        "รหัสอ้างอิง",
        "C20260830624214065059",
        "นายนัทธพงศ์ น***",
        "กรุงไทย",
        "XXX-X-XX322-1",
        "Ice Station",
        "(010753600031501)",
        "รหัสร้านค้า",
        "KB000002191004",
        "รหัสธุรกรรม",
        "KPS004KB0000021910",
        "04",
        "จำนวนเงิน",
        "25.00 บาท",
        "ค่าธรรมเนียม",
        "0.00 บาท",
        "วันที่ทำรายการ",
        "30 ส.ค. 2569 - 14:16",
    )

    // ── Krungthai · utility bill (multiple amounts on the slip) ──
    private val ktbWaterBill = text(
        "Krungthai",
        "กรุงไทย",
        "จ่ายบิลสำเร็จ",
        "รหัสอ้างอิง mKTB5344216038",
        "นายนัทธพงศ์ น***",
        "กรุงไทย",
        "XXX-X-XX322-1",
        "การประปานครหลวง (กปน.)",
        "(7337)",
        "ทะเบียนผู้ใช้น้ำ",
        "56807001",
        "จำนวนเงิน",
        "115.79 บาท",
        "ยอดค้างจ่ายประจำเดือน 10/08/69",
        "100.79 บาท",
        "ค่าธรรมเนียมเกินกำหนด",
        "15.00 บาท",
        "ค่าธรรมเนียม",
        "0.00 บาท",
        "วันที่ทำรายการ",
        "30 ส.ค. 2569 - 13:44",
    )

    // ── KBank K+ · shop payment ("จำนวน:" label, เลขที่รายการ ref) ──
    private val kbankFellow = text(
        "ชำระเงินสำเร็จ",
        "30 ส.ค. 69 11:12 น.",
        "K+",
        "นาย นัทธพงศ์ น",
        "ธ.กสิกรไทย",
        "xxx-x-x5474-x",
        "fellow and friends",
        "น.ส. ธัญชนก อินทร์ใจเอื้อ",
        "202608300844472",
        "เลขที่รายการ:",
        "016242111202BQR03170",
        "จำนวน:",
        "230.00 บาท",
        "ค่าธรรมเนียม:",
        "0.00 บาท",
        "สแกนตรวจสอบสลิป",
    )

    @Test
    fun `bbl transfer - amount with thousands separator, english layout`() {
        val slip = parser.parse(bblTransfer)
        assertEquals("Bangkok Bank", slip.bankName)
        assertEquals(1790.00, slip.amount)
        assertEquals("29 Aug 26, 17:27", slip.dateTime)
        assertEquals("2026082917275423003097308", slip.referenceId)
        assertEquals("นาย นัทธพงศ์", slip.sender)
        assertEquals("DARAINTR INTR", slip.receiver)
    }

    @Test
    fun `bbl merchant - picks transaction reference over merchant refs and fee`() {
        val slip = parser.parse(bblMerchant)
        assertEquals(115.00, slip.amount)
        assertEquals("2026090310054523002445308", slip.referenceId)
        assertTrue(slip.receiver.startsWith("บริษัท ซีพีเอฟ"))
    }

    @Test
    fun `ktb bill - buddhist era date, ref on line after label`() {
        val slip = parser.parse(ktbIceStation)
        assertEquals("Krungthai", slip.bankName)
        assertEquals(25.00, slip.amount)
        assertEquals("30 ส.ค. 2569 - 14:16", slip.dateTime)
        assertEquals("C20260830624214065059", slip.referenceId)
        assertEquals("นายนัทธพงศ์ น***", slip.sender)
        assertEquals("Ice Station", slip.receiver)
    }

    @Test
    fun `ktb water bill - main amount wins over arrears and late fee`() {
        val slip = parser.parse(ktbWaterBill)
        assertEquals(115.79, slip.amount)
        assertEquals("mKTB5344216038", slip.referenceId)
        assertEquals("30 ส.ค. 2569 - 13:44", slip.dateTime)
        assertEquals("การประปานครหลวง (กปน.)", slip.receiver)
    }

    @Test
    fun `kbank - bare thai amount label, date with thai time suffix`() {
        val slip = parser.parse(kbankFellow)
        assertEquals("KBank", slip.bankName)
        assertEquals(230.00, slip.amount)
        assertEquals("30 ส.ค. 69 11:12 น.", slip.dateTime)
        assertEquals("016242111202BQR03170", slip.referenceId)
        assertEquals("นาย นัทธพงศ์ น", slip.sender)
        assertEquals("น.ส. ธัญชนก อินทร์ใจเอื้อ", slip.receiver)
    }

    @Test
    fun `qr payload supplies the reference when no label survives ocr`() {
        val noRefLabel = text(
            "Krungthai", "จ่ายบิลสำเร็จ", "จำนวนเงิน", "25.00 บาท",
            "วันที่ทำรายการ", "30 ส.ค. 2569 - 14:16",
            // TLV: tag 00 len 06 "000001" · tag 01 len 21 "C2026…059"
            qr = listOf("00060000010121C20260830624214065059"),
        )
        assertEquals("C20260830624214065059", parser.parse(noRefLabel).referenceId)
    }

    @Test
    fun `non-slip text is rejected, never guessed`() {
        assertFailsWith<SlipParseException> {
            parser.parse(text("Shopping list", "milk 2, eggs 10", "call mom"))
        }
        assertFailsWith<SlipParseException> {
            // Bank-ish words but no amount/date/ref — must not fabricate.
            parser.parse(text("Bangkok Bank", "promotion!", "new savings account"))
        }
    }
}
