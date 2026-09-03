package com.shreddro.app.ocr

import android.util.Log
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
        val bitmap = ImageDecoding.decodeScaled(candidate.bytes, MAX_SIDE_PX)
            ?: return false
        try {
            val image = InputImage.fromBitmap(bitmap, 0)

            val text = runCatching { textRecognizer.process(image).await().text }
                .onFailure { Log.w(TAG, "text recognition failed", it) }
                .getOrDefault("")
            val barcodes = runCatching { barcodeScanner.process(image).await() }
                .onFailure { Log.w(TAG, "barcode scan failed", it) }
                .getOrDefault(emptyList())

            val thai = containsThai(text)
            val keywords = matchedKeywords(text)
            val brand = matchedBrand(text)
            val qr = hasBankQr(barcodes)

            // ML Kit's bundled recognizer is Latin-only, so `thai` is almost
            // never true on-device; a dense cluster of English slip keywords
            // (Bualuang prints "Successful / Amount / THB / Fee / Reference")
            // or a wallet/bank brand string printed in Latin letters
            // (Paotang's "G-Wallet ID", KBank's "K PLUS") is treated as strong
            // evidence on its own — Paotang slips carry no QR at all.
            var score = 0
            if (thai) score += 1
            if (keywords.size >= STRONG_KEYWORD_HITS) score += 2
            else if (keywords.isNotEmpty()) score += 1
            if (brand != null) score += 2
            if (qr) score += 2

            Log.d(
                TAG,
                "${candidate.displayName}: score=$score thai=$thai keywords=$keywords " +
                    "brand=$brand qr=$qr/${barcodes.size} textLen=${text.length} " +
                    "${bitmap.width}x${bitmap.height}",
            )
            return score >= 2
        } finally {
            bitmap.recycle()
        }
    }

    internal fun containsThai(text: String): Boolean =
        text.count { it in '฀'..'๿' } >= MIN_THAI_CHARS

    internal fun containsSlipKeywords(text: String): Boolean = matchedKeywords(text).isNotEmpty()

    internal fun matchedKeywords(text: String): List<String> {
        val lower = text.lowercase()
        return KEYWORDS.filter { lower.contains(it) }
    }

    internal fun matchedBrand(text: String): String? {
        val lower = text.lowercase()
        // Whole-word match: "samsung wallet" must not satisfy "g wallet".
        return BRAND_MARKERS.firstOrNull { marker ->
            Regex("(^|[^a-z])" + Regex.escape(marker) + "([^a-z]|$)").containsMatchIn(lower)
        }
    }

    private fun hasBankQr(barcodes: List<Barcode>): Boolean =
        barcodes.any { code ->
            val raw = code.rawValue ?: return@any false
            val hit = isBankQrPayload(raw)
            Log.d(TAG, "  qr ${if (hit) "bank" else "other"}: ${raw.take(QR_LOG_PREFIX)}…")
            hit
        }

    /**
     * Verified on-device (K PLUS, Bualuang mBanking, Krungthai NEXT): every
     * Thai slip carries the Thai Bankers' Association *slip-verification* mini
     * QR — a TLV string whose tag 00 wraps `0006000001` (API id 000001), a
     * bank code and the transaction reference, e.g.
     * `0041000600000101030040220<ref>5102TH9104<crc>`. That is NOT the EMVCo
     * PromptPay payment QR (`000201…`), which is what the payer scans, so
     * accept both plus bank deep-link hosts.
     */
    internal fun isBankQrPayload(raw: String): Boolean =
        SLIP_VERIFY_QR.containsMatchIn(raw) ||
            raw.startsWith("000201") ||
            (raw.startsWith("00") && raw.contains("5102TH")) ||
            BANK_QR_HOST_FRAGMENTS.any { raw.contains(it, ignoreCase = true) }

    private companion object {
        const val TAG = "Shreddro.Validator"
        const val MIN_THAI_CHARS = 8
        const val STRONG_KEYWORD_HITS = 3
        const val QR_LOG_PREFIX = 24
        /** tag 00 + len + API id "000001": TBA slip-verification QR. */
        val SLIP_VERIFY_QR = Regex("^00\\d{2}0006000001")
        /** Longest side after downsampling; slips are ≤ ~3200 px screenshots. */
        const val MAX_SIDE_PX = 2048
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
        /**
         * Latin-letter brand strings the Latin recognizer reads reliably off
         * real slips. Lower-case; matched as substrings of the lower-cased
         * text. "g-wallet" = Paotang (เป๋าตัง) government wallet receipts.
         */
        val BRAND_MARKERS = listOf(
            "g-wallet", "g wallet", "paotang", "k plus", "kplus", "kasikorn",
            "bualuang", "bangkok bank", "krungthai", "scb easy", "krungsri", "ttb",
        )
        val BANK_QR_HOST_FRAGMENTS = listOf(
            "kasikornbank", "kplus", "scb", "krungthai", "ktb",
            "bangkokbank", "bualuang", "krungsri", "ttb", "gsb", "promptpay",
        )
    }
}
