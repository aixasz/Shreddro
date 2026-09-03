# Real Slip Ground Truth (Owner-Provided Samples)

Five real slips from the owner's three banks, used to validate the gate
heuristics, the LLM prompt, and the parser (see `GoldenSlipSamplesTest`).
The images themselves are **not** committed (personal financial data); this
file records the expected contract output and what each sample taught us.

| # | Bank / App | Layout | Amount | Date format | Primary reference label |
|---|---|---|---|---|---|
| 1 | Bangkok Bank | P2P transfer, English UI | 1,790.00 | `29 Aug 26, 17:27` (CE, 2-digit yr) | `Transaction reference` (bank ref also present) |
| 2 | Bangkok Bank | Merchant QR payment, Thai receiver | 115.00 | `03 Sep 26, 10:05` | `Transaction reference` |
| 3 | Krungthai NEXT | Merchant bill payment (จ่ายบิลสำเร็จ) | 25.00 | `30 ส.ค. 2569 - 14:16` (Buddhist era) | `รหัสอ้างอิง` (also `รหัสธุรกรรม`) |
| 4 | Krungthai NEXT | Utility bill — Waterworks (กปน.) | 115.79 | `30 ส.ค. 2569 - 13:44` | `รหัสอ้างอิง` (`mKTB…`) |
| 5 | KBank K+ | Shop payment (ชำระเงินสำเร็จ) | 230.00 | `30 ส.ค. 69 11:12 น.` (BE, 2-digit yr) | `เลขที่รายการ` |

## Findings folded back into the implementation

1. **Gate keywords widened** (`MlKitSlipValidator`): KBank prints `จำนวน:`
   (not `จำนวนเงิน`); Krungthai uses `รหัสอ้างอิง`/`รหัสธุรกรรม`; bill/merchant
   flows say `จ่ายบิลสำเร็จ`/`ชำระเงินสำเร็จ`, never `โอนเงิน`. Added those plus
   `ตรวจสอบสลิป`, `merchant`, `service code`. Every sample also carries a
   verification QR, which alone scores 2 of the required 2 gate points.
2. **Prompt hardened** (`GeminiSlipParser`): amounts must be plain JSON
   numbers (BBL prints `1,790.00` — a leaked separator is invalid JSON and now
   triggers the repair retry, verified by test); reference selection follows a
   label priority (`เลขที่รายการ` > `Transaction reference` > `รหัสอ้างอิง` >
   `รหัสธุรกรรม` > `Bank reference no.`); dates stay exactly as printed,
   Buddhist-era years included; bank is the **sender's** app brand, inferred
   from the logo when unprinted (bill payments never name the bank in text).
3. **Buddhist-era dates** are stored as printed per the contract. Any future
   analytics layer must convert BE→CE (2569 → 2026) at read time, not in the
   ledger.
4. **Masked accounts** (`086-8-xxx871`, `xxx-x-x5474-x`) belong in
   sender/receiver strings as printed — they are the only disambiguator when
   two contacts share a display name.

## Device session 2026-09-03 — S25 Ultra (Android 16), 201 gallery images

Findings from the first on-device run, all folded into fixtures under
`core/src/test/resources/ocr-dumps/`:

- **Bank apps save slips to their own folders** (`Pictures/K PLUS`,
  `Pictures/Bualuang mBanking`, `Pictures/Krungthai NEXT`, `Pictures/PaoTang`).
  Discovery now scans every image bucket; the validator is the gate.
- **Slip-verification QR** (Thai Bankers' Association): `00 41 [00 06 000001 |
  01 03 <bank> | 02 20 <ref>] 51 02 TH 91 04 <crc>` on every K PLUS / Bualuang /
  Krungthai slip. The reference is tag 02 of the nested TLV and beats OCR.
- **K PLUS** (990 px wide over a watermark): amount line dissolves unless the
  image is upscaled to ~2600 px and flattened to high-contrast grayscale
  before Tesseract. Residual readings handled by the template: `13700 บาท`
  (lost decimal point → 137.00), `2900um` (บาท read as `um`), labels
  `จ้านวน:` / `เลขทีรายการ:`, months read as Latin (`29 a.m. 69`).
- **Paotang G-Wallet** (`paotang-gwallet.txt`): no QR; `G-Wallet ID:` line;
  whole-baht `จำนวนเงินที่ชำระ 30 บาท` (net of the ไทยช่วยไทย subsidy line);
  32-hex reference often garbled with Thai glyphs → reference optional.
- Camera RAW / oversized files are excluded in the MediaStore query
  (MIME + 20 MB cap); decoding is always downsampled (an 87 MB `.RAF` OOM'd
  the first run).

