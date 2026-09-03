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
        val amount = extractAmount(lines, bank)
            ?: throw SlipParseException("Template parser: amount not found")
        val dateTime = extractDate(lines)
            ?: throw SlipParseException("Template parser: date not found")
        val reference = extractReference(lines, input.qrPayloads)
            ?: if (bank in REFERENCE_OPTIONAL_BANKS) "" else {
                throw SlipParseException("Template parser: reference not found")
            }
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
        // SARA AM read as MAI THO on the K PLUS amount label ("จ้านวน:");
        // จ้านวน is not a Thai word, so the rewrite cannot hit real text.
        .replace("จ้านวน", "จำนวน")
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

    private fun extractAmount(lines: List<String>, bank: String): Double? {
        // Label-anchored first (จำนวนเงิน before bare จำนวน), skipping fee lines.
        for (label in AMOUNT_LABELS) {
            val idx = lines.indexOfFirst {
                it.contains(label, ignoreCase = true) && !isFeeLine(it)
            }
            if (idx < 0) continue
            for (j in idx..minOf(idx + 2, lines.lastIndex)) {
                if (isFeeLine(lines[j]) && j != idx) break
                moneyOn(lines[j])?.let { return it }
                // Whole-baht on the label line itself ("จำนวนเงินที่ชำระ 30 บาท",
                // Paotang). Only accepted right after the label AND followed by
                // a currency word, so a stray digit near the label can't win.
                if (j == idx) wholeBahtOn(lines[j].substringAfter(label))?.let { return it }
                // Lost decimal point on the value line under the label
                // ("13700 บาท" for 137.00, "2900um" for 29.00 — real K PLUS
                // OCR over the watermark). Banks in this set ALWAYS print two
                // decimals, so a dot-less run of digits + currency token can
                // only be satang-inclusive; the value is exact, not guessed.
                if (j > idx && bank in ALWAYS_TWO_DECIMALS) {
                    impliedDecimalsOn(lines[j])?.let { return it }
                }
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
        // Thousands separator dropped by OCR but decimals kept ("13700.00").
        UNGROUPED_MONEY.find(line)?.let {
            return it.value.toDoubleOrNull()?.takeIf { v -> v > 0.0 }
        }
        // OCR sometimes reads the decimal point as a comma ("230,00 บาท").
        return COMMA_MONEY.find(line)?.value
            ?.replace(".", "")?.replace(',', '.')
            ?.toDoubleOrNull()?.takeIf { it > 0.0 }
    }

    private fun impliedDecimalsOn(line: String): Double? {
        if (line.contains('.') || line.contains(',')) return null // a real separator survived
        val digits = IMPLIED_DECIMALS.find(line)?.groupValues?.get(1) ?: return null
        return digits.toLongOrNull()?.let { it / 100.0 }?.takeIf { it > 0.0 }
    }

    private fun wholeBahtOn(text: String): Double? =
        WHOLE_BAHT.find(text)?.groupValues?.get(1)
            ?.replace(",", "")?.toDoubleOrNull()?.takeIf { it > 0.0 }

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
        // LOOSE_DATE last: tha+eng Tesseract sometimes reads a Thai month
        // abbreviation as Latin letters ("29 a.m. 69" for ส.ค., "2 AY. 2569"
        // for ก.ย.). It requires a dotted month token AND a time right after,
        // so it cannot fire on account numbers or amounts.
        val m = THAI_DATE.find(line) ?: ENGLISH_DATE.find(line) ?: NUMERIC_DATE.find(line)
        ?: LOOSE_DATE.find(line) ?: return null
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
        // The slip-verification QR is machine-readable and carries the exact
        // transaction reference; OCR of the same string on-device produced
        // "016240120003AQRO09229" (O for 0) and "0162401141" (truncated), so
        // when a QR decoded, it wins over the printed text.
        qrPayloads.firstNotNullOfOrNull { refFromQr(it) }?.let { return it }

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
        return null
    }

    /**
     * Thai Bankers' Association slip-verification QR, as printed on every
     * K PLUS / Bualuang / Krungthai NEXT slip seen on-device:
     *
     *     00 41 [ 00 06 000001 | 01 03 <bank code> | 02 20 <transaction ref> ]
     *     51 02 TH   91 04 <crc>
     *
     * The reference is tag 02 of the NESTED TLV inside tag 00. Taking the
     * whole tag-00 value ("0006000001010300402200…") was a real bug that
     * reached the ledger, so the structured path is tried first and the
     * token heuristic only backs it up for non-standard payloads.
     */
    private fun refFromQr(payload: String): String? {
        val outer = tlvMap(payload)
        val inner = outer["00"]?.takeIf { it.startsWith(SLIP_VERIFY_API_ID) }?.let(::tlvMap)
        inner?.get("02")?.takeIf { REF_TOKEN.matches(it) }?.let { return it }

        val candidates = outer.values
            .flatMap { value -> REF_TOKEN.findAll(value).map { it.value } }
        // A slip reference mixes letters and digits far more often than the
        // surrounding numeric TLV plumbing; prefer that, then longest.
        return candidates.firstOrNull { c -> c.any(Char::isLetter) && c.any(Char::isDigit) }
            ?: candidates.maxByOrNull { it.length }
    }

    /** Walks tag(2)+length(2)+value TLV into tag -> value; bails quietly on malformed input. */
    private fun tlvMap(payload: String): Map<String, String> {
        val values = linkedMapOf<String, String>()
        var i = 0
        while (i + 4 <= payload.length) {
            val tag = payload.substring(i, i + 2)
            if (!tag.all(Char::isDigit)) break
            val len = payload.substring(i + 2, i + 4).toIntOrNull() ?: break
            val end = i + 4 + len
            if (end > payload.length) break
            values[tag] = payload.substring(i + 4, end)
            i = end
        }
        return values
    }

    // ── parties (best effort — blank is acceptable, wrong is not) ────────────

    private fun extractParties(lines: List<String>): Pair<String, String> {
        // Paotang (G-Wallet): payer name sits directly above "G-Wallet ID:",
        // merchant directly below it. Both lines carry an OCR'd avatar glyph
        // ("a นัทธพงศ์", "Ww บ้านกับข้าว") which cleanName cannot strip
        // because it reads as letters — drop a 1–2 letter Latin prefix.
        val walletIdx = lines.indexOfFirst { it.contains("G-Wallet ID", ignoreCase = true) }
        if (walletIdx >= 0) {
            fun party(i: Int) = lines.getOrNull(i)?.let(::cleanName)
                ?.replace(GLYPH_PREFIX, "") ?: ""
            return party(walletIdx - 1) to party(walletIdx + 1)
        }

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
            // Paotang (เป๋าตัง) G-Wallet receipts — government co-pay schemes
            // such as ไทยช่วยไทย. Listed LAST: a K PLUS slip paying INTO a
            // wallet prints "พร้อมเพย์ อี-วอลเล็ต / G-Wallet" and must stay
            // KBank; a real Paotang receipt names no bank at all.
            "Paotang" to listOf("G-Wallet", "GWallet", "เป๋าตัง", "ไทยช่วยไทย", "Paotang"),
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
        // "เลขทีรายการ" (tone mark dropped) is a real K PLUS OCR reading.
        val REFERENCE_LABELS = listOf(
            "เลขที่รายการ", "เลขทีรายการ", "Transaction reference", "างอิง",
            "รหัสธุรกรรม", "Bank reference",
        )
        val LABEL_WORDS = listOf(
            "จำนวน", "ค่าธรรมเนียม", "รหัส", "เลขที่", "วันที่", "ทะเบียน",
            "amount", "reference", "fee", "service code", "merchant",
            "transaction", "successful", "สำเร็จ", "scan", "สแกน",
        )

        /**
         * Paotang's 32-hex reference comes back from tha+eng Tesseract with
         * Thai glyphs substituted for a–f ("อธส116ส๕909๒…") often enough that
         * demanding it would park most G-Wallet receipts. Amount/date/parties
         * are reliable there, so the row is logged with a blank reference.
         */
        val REFERENCE_OPTIONAL_BANKS = setOf("Paotang")

        /** Templates that print satang on every amount line (never "30 บาท"). */
        val ALWAYS_TWO_DECIMALS = setOf("KBank", "Bangkok Bank", "Krungthai", "SCB", "Krungsri", "TTB", "GSB")

        val MONEY = Regex("""\d{1,3}(?:,\d{3})*\.\d{2}""")
        val UNGROUPED_MONEY = Regex("""(?<![\d.,])\d{4,}\.\d{2}(?!\d)""")
        // ≥3 digits (so at least 1.00) glued to a currency token; "um"/"un" are OCR of บาท.
        val IMPLIED_DECIMALS = Regex("""(?<![\d.,\-])(\d{3,})\s*(?:บาท|บยาท|um|un)""")
        // Integer baht + currency word; "un"/"unin"/"บยาท" are real OCR readings of บาท.
        val WHOLE_BAHT = Regex("""(?<![\d.,\-])(\d{1,3}(?:,\d{3})*)\s*(?:บาท|บยาท|un|THB|Baht)""")
        const val SLIP_VERIFY_API_ID = "0006000001"
        val LOOSE_DATE = Regex("""\b\d{1,2}\s?[^\s\d]{1,6}\.\s?\d{2,4}(?=\s+\d{1,2}:\d{2})""")
        val GLYPH_PREFIX = Regex("""^[A-Za-z]{1,2}\s+""")
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
