# Shreddro 🧾🧹

**Thai bank-slip sweeper.** Detects Thai banking transaction slips in your gallery, reads them with 100% on-device OCR (no AI API, no network), logs the data to *your own* Google Sheet or Excel Online workbook, archives the raw image into a private on-device vault, and purges the original from your camera roll (and therefore from Google/Apple Photos timelines) — with a single system consent dialog per batch.

- 📄 **[Product Requirement Document](docs/PRD.md)**
- 🏗️ **[Unified Architecture Blueprint](docs/ARCHITECTURE.md)**

## Supported banks

| Bank | Detection | Field extraction | Status |
| --- | --- | --- | --- |
| Kasikornbank (K+ / KBank) | ✅ | ✅ | **Verified against real slips** ([test fixtures](core/src/test/resources/ocr-dumps/)) |
| Krungthai (KTB / NEXT) | ✅ | ✅ | **Verified against real slips** — transfers, bill payments, utilities |
| Bangkok Bank (BBL) | ✅ | ✅ | **Verified against real slips** — transfers and merchant QR payments |
| Paotang (เป๋าตัง) G-Wallet receipts | ✅ | ✅ | **Verified against real slips** — ไทยช่วยไทย co-pay receipts; whole-baht amounts, reference optional when OCR garbles the hex code |
| SCB, Krungsri, TTB, GSB | ✅ | ⚠️ | Bank recognized; extraction uses the generic Thai labels and is **untested** — [contribute a slip fixture!](core/src/test/kotlin/com/shreddro/core/RealOcrDumpTest.kt) |

Slips that can't be read confidently are never guessed — they land in the in-app
**Needs review** queue for one-tap retry or manual entry. To add or improve a
bank, extend the templates in
[ThaiSlipTemplateParser.kt](core/src/main/kotlin/com/shreddro/core/parse/ThaiSlipTemplateParser.kt)
and add an anonymized OCR dump as a test fixture (see
[docs/SLIP-SAMPLES.md](docs/SLIP-SAMPLES.md)).

## Install (no Play Store needed)

1. On your Android phone (Android 10+), open the
   [**latest release**](https://github.com/aixasz/Shreddro/releases/latest)
   and download the APK that matches your device:
   - `app-arm64-v8a-release.apk` — most phones from ~2017 onward (try this first)
   - `app-armeabi-v7a-release.apk` — older 32-bit devices
   - `app-universal-release.apk` — works everywhere (larger download)
2. Tap the downloaded file. When Android asks, allow your browser/file manager
   to **install unknown apps** (Settings → Apps → Special app access → Install
   unknown apps) — this prompt appears once per installing app.
3. Confirm the install. If Play Protect shows a warning (normal for apps
   outside the Play Store), choose **Install anyway** — you can verify what
   you're installing because the entire source code is this repository, and
   every APK is built and signed by the public
   [GitHub Actions workflow](.github/workflows/android.yml).
4. Open Shreddro, grant photo access, and tap **Scan Gallery Now**. Cloud sync
   (Google Sheets / Excel) is optional — the app is fully functional offline.

**Updating:** install a newer release APK over the old one; your ledger,
archive, and settings are preserved. Tools like
[Obtainium](https://github.com/ImranR98/Obtainium) can auto-update from this
repo's releases if you prefer.

## Repository layout

```
core/                 Pure Kotlin business logic (KMP-ready; zero android.* imports)
app/                  Android shell: UI, MediaStore, ML Kit, AppAuth, Retrofit adapters
backend/apps-script/  Google Apps Script Web App gateway (user-deployed)
docs/                 PRD + architecture blueprint
```

## Pipeline (one image)

```text
gallery image ─► ML Kit gate (Thai text + bank QR)
   ─► 100% on-device parse: Tesseract OCR (tha+eng) + QR decode + per-bank template rules
   ─► local CSV ledger (always) ─► fan-out: Apps Script → Google Sheet tab per bank
                                             Graph API  → Excel table per bank
                                             Drive / OneDrive ← raw image per bank
   ─► verified copy to BankSlips_Archive/ (+ .nomedia) ─► MediaStore.createDeleteRequest()
```

Parsing is fully offline — no AI API, no key, and slip images never leave the
device (cloud sync of the ledger/binary is optional and user-configured).

Invariant: **an original is never deleted until the transaction row is durably logged locally and the archive copy is hash-verified.**

## Building

Requires Android Studio (JDK 17, AGP 8.5). This repo intentionally ships no Gradle wrapper binary; generate one with `gradle wrapper --gradle-version 8.7` or open in Android Studio.

Build-time config in `local.properties` (never committed; CI supplies the
same keys as GitHub Secrets via `-P` properties):

```properties
shreddro.googleClientId=xxxx.apps.googleusercontent.com
shreddro.msClientId=00000000-0000-0000-0000-000000000000
```

These OAuth client ids are identifiers, not secrets — they are bound to the
package name and signing certificate. **Real secrets never ship in the APK**:
the Gemini API key and your Apps Script URL/secret are entered at runtime in
the app's *Cloud & AI settings* and stored in Keystore-encrypted prefs
(`local.properties` values for them work as a dev-build convenience only).

Provider console setup steps are documented in [AuthConfig.kt](app/src/main/kotlin/com/shreddro/app/auth/AuthConfig.kt); Apps Script deployment steps are at the top of [Code.gs](backend/apps-script/Code.gs).

## Tests

`:core` carries pure-JVM unit tests (JSON contract parsing, RFC-4180 CSV formatting, sync routing/fallback matrix):

```bash
./gradlew :core:test
```

## Phase status

- **Phase 1 (this code):** full pipeline, Google + Microsoft engines, local CSV fallback, gallery sweeper.
- **Phase 2:** review queue UI, sync-drain workers, notification digests.
- **Phase 3:** `:core` → Kotlin Multiplatform `commonMain`; iOS shell (PhotoKit + `PHAssetChangeRequest.deleteAssets()`).
