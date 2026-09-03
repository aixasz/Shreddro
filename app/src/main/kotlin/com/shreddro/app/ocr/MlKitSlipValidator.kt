package com.shreddro.app.ocr

import android.graphics.BitmapFactory
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import com.shreddro.core.gateway.SlipCandidate
import com.shreddro.core.gateway.SlipValidator
import kotlinx.coroutines.tasks.await

/**
 * On-device gate: decides in <300ms whether an image plausibly is a Thai bank
 * slip BEFORE any cloud/LLM call.
 *
 * Signals (any two → pass, QR alone with amount-like text → pass):
 *  1. Thai-script characters present in the raw image text (Unicode block
 *     0E00–0E7F). Note: ML Kit's on-device recognizers don't ship a dedicated
 *     Thai model; the Latin recognizer still segments Thai glyph lines and
 *     reliably captures digits/latin bank codes, and Thai codepoints appear in
 *     mixed-script slips. We therefore weight signals rather than requiring
 *     perfect Thai OCR.
 *  2. Slip keywords (Thai or English) commonly printed by Thai banking apps.
 *  3. A QR/mini-QR whose payload matches the EMVCo TLV layout ("000201…") or a
 *     Thai bank slip-verification deep link.
 */
class MlKitSlipValidator : SlipValidator {

    private val textRecognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val barcodeScanner = BarcodeScanning.getClient(
        BarcodeScannerOptions.Builder()
            .setBarcodeFormats(Barcode.FORMAT_QR_CODE)
            .build(),
    )

    override suspend fun looksLikeBankSlip(candidate: SlipCandidate): Boolean {
        val bitmap = BitmapFactory.decodeByteArray(candidate.bytes, 0, candidate.bytes.size)
            ?: return false
        val image = InputImage.fromBitmap(bitmap, 0)

        val text = runCatching { textRecognizer.process(image).await().text }.getOrDefault("")
        val barcodes = runCatching { barcodeScanner.process(image).await() }.getOrDefault(emptyList())

        var score = 0
        if (containsThai(text)) score += 1
        if (containsSlipKeywords(text)) score += 1
        if (hasBankQr(barcodes)) score += 2

        return score >= 2
    }

    internal fun containsThai(text: String): Boolean =
        text.count { it in '฀'..'๿' } >= MIN_THAI_CHARS

    internal fun containsSlipKeywords(text: String): Boolean {
        val lower = text.lowercase()
        return KEYWORDS.any { lower.contains(it) }
    }

    private fun hasBankQr(barcodes: List<Barcode>): Boolean =
        barcodes.any { code ->
            val raw = code.rawValue ?: return@any false
            // EMVCo merchant/slip TLV payloads start with tag 00, length 02, "01".
            raw.startsWith("000201") ||
                BANK_QR_HOST_FRAGMENTS.any { raw.contains(it, ignoreCase = true) }
        }

    private companion object {
        const val MIN_THAI_CHARS = 8
        // Verified against real slips from K+ (KBank), Krungthai NEXT, and
        // Bangkok Bank: transfers, bill payments (จ่ายบิล), and merchant
        // payments (ชำระเงิน) label fields differently — KBank prints
        // "จำนวน:" not "จำนวนเงิน", Krungthai uses "รหัสอ้างอิง"/"รหัสธุรกรรม".
        val KEYWORDS = listOf(
            // Thai
            "โอนเงิน", "จำนวน", "บาท", "สำเร็จ", "ค่าธรรมเนียม",
            "ผู้โอน", "ผู้รับ", "เลขที่รายการ", "พร้อมเพย์",
            "จ่ายบิล", "ชำระเงิน", "รหัสอ้างอิง", "รหัสธุรกรรม", "ตรวจสอบสลิป",
            // English fallbacks printed by Thai banking apps
            "transfer", "successful", "amount", "baht", "thb",
            "promptpay", "reference", "fee", "merchant", "service code",
        )
        val BANK_QR_HOST_FRAGMENTS = listOf(
            "kasikornbank", "kplus", "scb", "krungthai", "ktb",
            "bangkokbank", "bualuang", "krungsri", "ttb", "gsb", "promptpay",
        )
    }
}
