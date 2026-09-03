# Shreddro 🧾🧹

**Thai bank-slip sweeper.** Detects Thai banking transaction slips in your gallery, parses them with a Vision LLM, logs the data to *your own* Google Sheet or Excel Online workbook, archives the raw image into a private on-device vault, and purges the original from your camera roll (and therefore from Google/Apple Photos timelines) — with a single system consent dialog per batch.

- 📄 **[Product Requirement Document](docs/PRD.md)**
- 🏗️ **[Unified Architecture Blueprint](docs/ARCHITECTURE.md)**

## Repository layout

```
core/                 Pure Kotlin business logic (KMP-ready; zero android.* imports)
app/                  Android shell: UI, MediaStore, ML Kit, AppAuth, Retrofit adapters
backend/apps-script/  Google Apps Script Web App gateway (user-deployed)
docs/                 PRD + architecture blueprint
```

## Pipeline (one image)

```
gallery image ─► ML Kit gate (Thai text + bank QR) ─► Gemini Flash parse (strict JSON)
   ─► local CSV ledger (always) ─► fan-out: Apps Script → Google Sheet tab per bank
                                             Graph API  → Excel table per bank
                                             Drive / OneDrive ← raw image per bank
   ─► verified copy to BankSlips_Archive/ (+ .nomedia) ─► MediaStore.createDeleteRequest()
```

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
