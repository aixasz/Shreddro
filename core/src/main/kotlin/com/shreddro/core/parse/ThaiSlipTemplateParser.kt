package com.shreddro.core.parse

import com.shreddro.core.gateway.SlipParseException
import com.shreddro.core.model.TransactionSlip

/**
 * Raw on-device extraction of a slip image: OCR text lines (Tesseract tha+eng
 * on Android, Vision on iOS) plus decoded QR payloads (ML Kit barcode).
 */
data class ExtractedSlipText(
    val lines: List<String>,
    val qrPayloads: List<String> = emptyList(),
)

/**
 * OFFLINE slip parser: label-anchored template extraction for Thai bank
 * slips. Slips are app-rendered screenshots with per-bank templated layouts,
 * which makes rule-based extraction viable — and it runs with no network and
 * no API key, which the Vision-LLM path cannot.
 *
 * Templates are built from real KBank K+ / Krungthai NEXT / Bangkok Bank
 * slips (see docs/SLIP-SAMPLES.md). Anything that doesn't extract with
 * confidence throws [SlipParseException] so the composite chain can fall
 * through to the online LLM or the review queue — this parser must never
 * guess.
 */
class ThaiSlipTemplateParser {

    fun parse(input: ExtractedSlipText): TransactionSlip {
        val lines = input.lines.map { normalize(it) }.filter { it.isNotEmpty() }
        if (lines.isEmpty()) throw SlipParseException("No OCR text")

        val bank = detectBank(lines)
            ?: throw SlipParseException("Template parser: bank not recognized")
        val amount = extractAmount(lines)
            ?: throw SlipParseException("Template parser: amount not found")
        val dateTime = extractDate(lines)
            ?: throw SlipParseException("Template parser: date not found")
        val reference = extractReference(lines, input.qrPayloads)
            ?: throw SlipParseException("Template parser: reference not found")
        val (sender, receiver) = extractParties(lines)

        return TransactionSlip(
            bankName = bank,
            dateTime = dateTime,
            amount = amount,
            sender = sender,
            receiver = receiver,
            referenceId = reference,
        )
    }

    /**
     * Canonicalizes real Tesseract quirks observed on actual slips:
     * SARA AM decomposed to nikhahit+aa (จํานวน -> จำนวน) and C misread as €.
     */
    private fun normalize(line: String): String = line
        .replace("ํา", "ำ")
        .replace('€', 'C')
        .trim()

    // ── bank ─────────────────────────────────────────────────────────────────

    private fun detectBank(lines: List<String>): String? {
        val all = lines.joinToString("\n")
        return BANK_MARKERS.firstOrNull { (_, markers) ->
            markers.any { all.contains(it, ignoreCase = true) }
        }?.first
    }

    // ── amount ───────────────────────────────────────────────────────────────

    private fun extractAmount(lines: List<String>): Double? {
        // Label-anchored first (จำนวนเงิน before bare จำนวน), skipping fee lines.
        for (label in AMOUNT_LABELS) {
            val idx = lines.indexOfFirst {
                it.contains(label, ignoreCase = true) && !isFeeLine(it)
            }
            if (idx < 0) continue
            for (j in idx..minOf(idx + 2, lines.lastIndex)) {
                if (isFeeLine(lines[j]) && j != idx) break
                moneyOn(lines[j])?.let { return it }
            }
        }
        // Fallback: a non-fee line pairing a money value with a currency word.
        return lines.asSequence()
            .filter { line ->
                !isFeeLine(line) && CURRENCY_WORDS.any { line.contains(it, ignoreCase = true) }
            }
            .mapNotNull { moneyOn(it) }
            .firstOrNull()
    }

    private fun moneyOn(line: String): Double? {
        MONEY.find(line)?.let {
            return it.value.replace(",", "").toDoubleOrNull()?.takeIf { v -> v > 0.0 }
        }
        // OCR sometimes reads the decimal point as a comma ("230,00 บาท").
        return COMMA_MONEY.find(line)?.value
            ?.replace(".", "")?.replace(',', '.')
            ?.toDoubleOrNull()?.takeIf { it > 0.0 }
    }

    private fun isFeeLine(line: String) =
        line.contains("ค่าธรรมเนียม") || line.contains("fee", ignoreCase = true)

    // ── date ─────────────────────────────────────────────────────────────────

    private fun extractDate(lines: List<String>): String? {
        for (label in DATE_LABELS) {
            val idx = lines.indexOfFirst { it.contains(label) }
            if (idx < 0) continue
            for (j in idx..minOf(idx + 1, lines.lastIndex)) {
                dateOn(lines[j])?.let { return it }
            }
        }
        return lines.firstNotNullOfOrNull { dateOn(it) }
    }

    /** Returns the date-through-time span of the line, as printed. */
    private fun dateOn(line: String): String? {
        val m = THAI_DATE.find(line) ?: ENGLISH_DATE.find(line) ?: NUMERIC_DATE.find(line)
        ?: return null
        val time = TIME.find(line, m.range.last)
        val end = time?.range?.last ?: m.range.last
        val span = line.substring(m.range.first, minOf(end + 1, line.length)).trim()
        // KBank prints "… น." after the time — keep it, it's part of "as printed".
        return if (line.substring(minOf(end + 1, line.length)).trimStart().startsWith("น.")) {
            "$span น."
        } else span
    }

    // ── reference ────────────────────────────────────────────────────────────

    private fun extractReference(lines: List<String>, qrPayloads: List<String>): String? {
        for (label in REFERENCE_LABELS) {
            val idx = lines.indexOfFirst { it.contains(label, ignoreCase = true) }
            if (idx < 0) continue
            for (j in idx..minOf(idx + 2, lines.lastIndex)) {
                val tail = if (j == idx) {
                    lines[j].substringAfter(label).trimStart(':', ' ', '.', '#')
                } else lines[j]
                REF_TOKEN.find(tail)?.let { return it.value }
            }
        }
        // QR fallback: bank verification QRs are EMV-style TLV strings whose
        // inner values carry the transaction reference.
        return qrPayloads.firstNotNullOfOrNull { refFromQr(it) }
    }

    private fun refFromQr(payload: String): String? {
        val candidates = tlvValues(payload)
            .flatMap { value -> REF_TOKEN.findAll(value).map { it.value } }
        // A slip reference mixes letters and digits far more often than the
        // surrounding numeric TLV plumbing; prefer that, then longest.
        return candidates.firstOrNull { c -> c.any(Char::isLetter) && c.any(Char::isDigit) }
            ?: candidates.maxByOrNull { it.length }
    }

    /** Walks tag(2)+length(2)+value TLV; bails quietly on malformed input. */
    private fun tlvValues(payload: String): List<String> {
        val values = mutableListOf<String>()
        var i = 0
        while (i + 4 <= payload.length) {
            if (!payload.substring(i, i + 2).all(Char::isDigit)) break
            val len = payload.substring(i + 2, i + 4).toIntOrNull() ?: break
            val end = i + 4 + len
            if (end > payload.length) break
            values += payload.substring(i + 4, end)
            i = end
        }
        return values
    }

    // ── parties (best effort — blank is acceptable, wrong is not) ────────────

    private fun extractParties(lines: List<String>): Pair<String, String> {
        val fromIdx = lines.indexOfFirst { it.equals("From", true) || it == "จาก" }
        val toIdx = lines.indexOfFirst { it.equals("To", true) || it == "ไปยัง" || it == "ถึง" }
        if (fromIdx >= 0 && toIdx > fromIdx) {
            // Noise checks run on the RAW line (cleaning can strip the very
            // digits/dashes that mark an account line); cleaning is for output.
            val sender = lines.getOrNull(fromIdx + 1)?.takeIf { !isNoise(it) }?.let(::cleanName) ?: ""
            val receiver = lines.getOrNull(toIdx + 1)?.takeIf { !isNoise(it) }?.let(::cleanName) ?: ""
            if (sender.isNotEmpty() || receiver.isNotEmpty()) return sender to receiver
        }

        val named = lines.withIndex().filter { NAME_PREFIX.containsMatchIn(it.value) }
        val sender = named.getOrNull(0)?.value?.let(::cleanName) ?: ""
        val senderIdx = named.getOrNull(0)?.index ?: -1
        val receiver = named.getOrNull(1)?.value?.let(::cleanName)
            ?: lines.drop(senderIdx + 1)
                .firstOrNull { !isNoise(it) && cleanName(it).length >= 3 }
                ?.let(::cleanName)
            ?: ""
        return sender to receiver
    }

    /** Strips OCR'd icon glyphs and bullet junk off the front of a name line. */
    private fun cleanName(line: String) = line.trimStart { !it.isLetter() }.trim()

    /** Lines that are labels, banks, accounts, amounts — never a party name. */
    private fun isNoise(line: String): Boolean =
        line.none { it.isLetter() } ||
            MONEY.containsMatchIn(line) ||
            MASKED_ACCOUNT.containsMatchIn(line) ||
            BANK_WORDS.any { line.contains(it, ignoreCase = true) } ||
            LABEL_WORDS.any { line.contains(it, ignoreCase = true) }

    private companion object {
        // Order matters where markers overlap; first hit wins.
        val BANK_MARKERS = listOf(
            "Bangkok Bank" to listOf("Bangkok Bank", "ธนาคารกรุงเทพ", "บัวหลวง", "Bualuang"),
            // "งไทย" catches OCR corruptions of กรุงไทย (seen: "การงไทย");
            // no other Thai bank name contains that sequence.
            "Krungthai" to listOf("Krungthai", "กรุงไทย", "KTB", "งไทย"),
            "KBank" to listOf("กสิกรไทย", "KBank", "K PLUS", "KPLUS", "K+", "Kasikorn"),
            "SCB" to listOf("ไทยพาณิชย์", "SCB EASY"),
            "Krungsri" to listOf("กรุงศรี", "Krungsri"),
            "TTB" to listOf("ทีเอ็มบีธนชาต", "ttb"),
            "GSB" to listOf("ออมสิน", "GSB"),
        )
        val BANK_WORDS = listOf(
            "ธนาคาร", "ธ.", "งไทย", "กสิกรไทย", "กรุงเทพ", "ไทยพาณิชย์",
            "Bank", "Krungthai", "KBank", "Kasikorn", "Bualuang",
        )
        val AMOUNT_LABELS = listOf("จำนวนเงิน", "จำนวน", "Amount", "ยอดชำระ")
        val CURRENCY_WORDS = listOf("บาท", "THB", "Baht")
        val DATE_LABELS = listOf("วันที่ทำรายการ", "วันที่")
        // "างอิง" (suffix of อ้างอิง) also matches OCR-corrupted forms of the
        // label (seen: "รหัสอฮ้างอิง").
        val REFERENCE_LABELS = listOf(
            "เลขที่รายการ", "Transaction reference", "างอิง",
            "รหัสธุรกรรม", "Bank reference",
        )
        val LABEL_WORDS = listOf(
            "จำนวน", "ค่าธรรมเนียม", "รหัส", "เลขที่", "วันที่", "ทะเบียน",
            "amount", "reference", "fee", "service code", "merchant",
            "transaction", "successful", "สำเร็จ", "scan", "สแกน",
        )

        val MONEY = Regex("""\d{1,3}(?:,\d{3})*\.\d{2}""")
        val COMMA_MONEY = Regex("""\d{1,3}(?:\.\d{3})*,\d{2}(?!\d)""")
        val TIME = Regex("""\d{1,2}:\d{2}""")
        val THAI_DATE = Regex("""\d{1,2}\s?(ม\.ค\.|ก\.พ\.|มี\.ค\.|เม\.ย\.|พ\.ค\.|มิ\.ย\.|ก\.ค\.|ส\.ค\.|ก\.ย\.|ต\.ค\.|พ\.ย\.|ธ\.ค\.)\s?\d{2,4}""")
        val ENGLISH_DATE = Regex("""\d{1,2} (Jan|Feb|Mar|Apr|May|Jun|Jul|Aug|Sep|Oct|Nov|Dec)[a-z]* \d{2,4}""")
        val NUMERIC_DATE = Regex("""\d{1,2}/\d{1,2}/\d{2,4}""")
        val REF_TOKEN = Regex("""[A-Za-z0-9]{10,}""")
        val NAME_PREFIX = Regex("""(^|\s)(นาย|นาง|น\.ส\.|นส\.|บริษัท|MR\.?\s|MS\.?\s|MRS\.?\s)""", RegexOption.IGNORE_CASE)
        val MASKED_ACCOUNT = Regex("""[Xx\d*]{2,}[-·][Xx\d*-]{2,}""")
    }
}
