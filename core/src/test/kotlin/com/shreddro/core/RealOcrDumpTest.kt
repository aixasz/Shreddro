package com.shreddro.core

import com.shreddro.core.parse.ExtractedSlipText
import com.shreddro.core.parse.ThaiSlipTemplateParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * END-TO-END OCR acceptance: the fixtures under resources/ocr-dumps are the
 * UNEDITED output of Tesseract 5 (tha+eng, the exact fast models bundled in
 * app assets) run against the owner's five real slip images. They contain
 * genuine OCR damage — ำ decomposed to ํา, "€" for C, "230,00" for 230.00,
 * "รหัสอฮ้างอิง", "การงไทย", icon glyphs on name lines — and the template
 * parser must extract the money-critical fields regardless.
 */
class RealOcrDumpTest {

    private val parser = ThaiSlipTemplateParser()

    private fun parseDump(name: String) = parser.parse(
        ExtractedSlipText(
            checkNotNull(javaClass.getResourceAsStream("/ocr-dumps/$name")) { "missing $name" }
                .bufferedReader(Charsets.UTF_8).readLines(),
        ),
    )

    @Test
    fun `bbl transfer - real ocr`() {
        val slip = parseDump("bbl-transfer.txt")
        assertEquals("Bangkok Bank", slip.bankName)
        assertEquals(1790.00, slip.amount)
        assertEquals("29 Aug 26, 17:27", slip.dateTime)
        assertEquals("2026082917275423003097308", slip.referenceId)
        assertTrue(slip.sender.contains("นัทธพงศ์"), "sender was: ${slip.sender}")
        assertTrue(slip.receiver.contains("DARAINTR"), "receiver was: ${slip.receiver}")
    }

    @Test
    fun `bbl merchant - real ocr`() {
        val slip = parseDump("bbl-merchant.txt")
        assertEquals("Bangkok Bank", slip.bankName)
        assertEquals(115.00, slip.amount)
        assertEquals("03 Sep 26, 10:05", slip.dateTime)
        assertEquals("2026090310054523002445308", slip.referenceId)
        assertTrue(slip.receiver.contains("บริษัท"), "receiver was: ${slip.receiver}")
    }

    @Test
    fun `ktb ice station - real ocr with corrupted bank name and euro-sign ref`() {
        val slip = parseDump("ktb-ice-station.txt")
        assertEquals("Krungthai", slip.bankName)
        assertEquals(25.00, slip.amount)
        assertEquals("30 ส.ค. 2569 - 14:16", slip.dateTime)
        assertEquals("C20260830624214065059", slip.referenceId) // € normalized to C
        assertTrue(slip.sender.contains("นายนัทธพงศ์"), "sender was: ${slip.sender}")
        assertTrue(slip.receiver.contains("Station"), "receiver was: ${slip.receiver}")
    }

    @Test
    fun `ktb water bill - real ocr with corrupted ref label and rival amounts`() {
        val slip = parseDump("ktb-water-bill.txt")
        assertEquals("Krungthai", slip.bankName)
        assertEquals(115.79, slip.amount) // not the 100.79 arrears or 15.00 late fee
        assertEquals("30 ส.ค. 2569 - 13:44", slip.dateTime)
        assertEquals("MKTB5344216038", slip.referenceId)
        assertTrue(slip.receiver.contains("การประปานครหลวง"), "receiver was: ${slip.receiver}")
    }

    @Test
    fun `kbank - real ocr with comma-decimal amount`() {
        val slip = parseDump("kbank-fellow.txt")
        assertEquals("KBank", slip.bankName)
        assertEquals(230.00, slip.amount) // OCR printed "230,00 บาท"
        assertEquals("30 ส.ค. 69 11:12 น.", slip.dateTime)
        assertEquals("016242111202BQR03170", slip.referenceId)
        assertTrue(slip.sender.contains("นัทธพงศ์"), "sender was: ${slip.sender}")
    }
}
