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

## 🙋 Volunteer testers wanted — help Shreddro reach Google Play

Google Play requires a new developer account to run a **closed test with at
least 12 testers for 14 consecutive days** before an app is allowed into
production. Shreddro is 100% free and open source, so we are asking the
community to help us over that line.

**What you need**

- An Android phone (Android 10+) with a Google account
- Bank slips in your gallery (KBank, Krungthai, Bangkok Bank, Paotang… or any
  Thai bank — unsupported ones are exactly what we want to hear about)
- 14 days during which you keep the app installed and open it now and then

**How to join**

1. Send an email to **[aixasz@hotmail.com](mailto:aixasz@hotmail.com?subject=Shreddro%20tester)**
   with the subject `Shreddro tester` and the **Google account email** you use
   on your phone (that is what Google Play needs to add you to the test track).
   Your GitHub username is optional — include it if you want the tester badge
   below.
2. You will receive the closed-testing opt-in link. Tap **Become a tester**,
   then install Shreddro from Google Play.
3. Use it on real slips. Report anything odd (misread amount, missed slip,
   crash) in [GitHub Issues](https://github.com/aixasz/Shreddro/issues) —
   OCR text only, please never post a slip image with account numbers.
4. Stay opted in for at least 14 days. That is all.

Your email is used only to add you to the Play test track and is never
published. Everything the app reads stays on your device (see
[Pipeline](#pipeline-one-image)).

**What you get**

- 🏅 A **Shreddro Tester** badge on [Holopin](https://holopin.io) for your
  GitHub profile (issued to your GitHub username once the test completes).
- 📓 Your name on the **Testers wall** below.
- Early access to every build before it ships, and a say in what gets fixed.

Progress and Q&A live in the pinned
[**Call for testers** issue](https://github.com/aixasz/Shreddro/issues/1).

### Testers wall

_Thank you! Names are added here (with permission) as testers join._

<!-- testers-wall-start -->
<!-- testers-wall-end -->

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
   ─► local CSV ledger (always) ─► fan-out to each linked cloud, laid out as
                                     Shreddro/<bank>/<slip image>            (Drive / OneDrive)
                                     Shreddro/Shreddro Transactions          (one Google Sheet / .xlsx: +1 row with bank + image_file)
                                   folders and files are reused when they already exist
   ─► verified copy to BankSlips_Archive/ (+ .nomedia) ─► MediaStore.createDeleteRequest()
```

Parsing is fully offline — no AI API, no key, and slip images never leave the
device (cloud sync of the ledger/binary is optional and user-configured).

Invariant: **an original is never deleted until the transaction row is durably logged locally and the archive copy is hash-verified** (by default the archive is the same downsized 1600 px JPEG the cloud receives; a setting keeps full originals instead).

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
an optional Apps Script URL/secret (legacy master-sheet mode) is entered at
runtime in the app's *Cloud sync settings* and stored in Keystore-encrypted
prefs. By default no backend is needed: the app writes per-bank Google Sheets
and Excel workbooks directly with the linked account's token.

Provider console setup steps (enable custom URI scheme, Drive API + Sheets API, test users) are documented in [AuthConfig.kt](app/src/main/kotlin/com/shreddro/app/auth/AuthConfig.kt); the optional Apps Script deployment is described at the top of [Code.gs](backend/apps-script/Code.gs).

## Tests

`:core` carries pure-JVM unit tests (JSON contract parsing, RFC-4180 CSV formatting, sync routing/fallback matrix):

```bash
./gradlew :core:test
```

## Phase status

- **Phase 1 (this code):** full pipeline, Google + Microsoft engines, local CSV fallback, gallery sweeper.
- **Phase 2:** review queue UI, sync-drain workers, notification digests.
- **Phase 3:** `:core` → Kotlin Multiplatform `commonMain`; iOS shell (PhotoKit + `PHAssetChangeRequest.deleteAssets()`).
