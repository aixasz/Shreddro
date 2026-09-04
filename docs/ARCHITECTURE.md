# Shreddro — Unified Architecture Blueprint

**Scope:** Android-first, with a strict platform-agnostic core so the business layer lifts into Kotlin Multiplatform (KMP) unchanged in Phase 3.

---

## 1. Layered Overview

```
┌────────────────────────────────────────────────────────────────────┐
│  :app  (Android shell — swap for iOS shell in Phase 3)             │
│  UI (Compose) · WorkManager · ActivityResultLauncher · Notifs      │
│  ┌──────────────────────────────────────────────────────────────┐  │
│  │ Platform Adapters (implement :core interfaces)               │  │
│  │  StorageCoordinator      → MediaStore/SAF  (iOS: PhotoKit)   │  │
│  │  MlKitSlipValidator      → ML Kit          (iOS: Vision)     │  │
│  │  GeminiSlipParser        → Retrofit/OkHttp (shared HTTP ok)  │  │
│  │  AppsScriptClient        → Retrofit                          │  │
│  │  GraphExcelClient        → Retrofit                          │  │
│  │  AndroidCsvSink          → java.io (iOS: FileManager)        │  │
│  │  AppAuthManager          → AppAuth-Android (iOS: AppAuth-iOS)│  │
│  └──────────────────────────────────────────────────────────────┘  │
└───────────────────────────────▲────────────────────────────────────┘
                                │ interfaces only — no android.* below
┌───────────────────────────────┴────────────────────────────────────┐
│  :core  (pure Kotlin/JVM today → commonMain tomorrow)              │
│  model/      TransactionSlip, CloudProvider, SyncTarget, results   │
│  pipeline/   SlipPipeline state machine (gate→parse→log→archive)   │
│  parse/      SlipJsonParser (LLM JSON → contract, validation)      │
│  csv/        CsvFormatter (RFC 4180, header mgmt)                  │
│  gateway/    SlipValidator, SlipParser, SpreadsheetGateway,        │
│              BinaryStorageGateway, LedgerSink, MediaVault (ports)  │
│  repo/       TransactionRepository (routing + fan-out + fallback)  │
└────────────────────────────────────────────────────────────────────┘
```

**Rule:** `:core` has **zero** Android imports. Every platform capability is a `gateway` interface (hexagonal ports). `:app` provides the adapters. Phase 3 = move `:core` sources to `commonMain`, re-implement adapters on iOS.

## 2. Pipeline State Machine

Each discovered image walks this machine; every transition is persisted so the pipeline is resumable and idempotent (keyed by image SHA-256):

```
DISCOVERED ─gate fail──────────────► SKIPPED (hash remembered)
    │ gate pass (Thai text/QR)
    ▼
VALIDATED ─parse fail ×2───────────► NEEDS_REVIEW
    │ LLM JSON conforms to contract
    ▼
PARSED ───────► LOGGED_LOCAL (CSV/queue row — ALWAYS, even when online)
    │ per linked provider (fan-out, independent):
    ├─► SHEET_SYNCED_GOOGLE / SHEET_SYNCED_MS
    ├─► BINARY_SYNCED_GOOGLE / BINARY_SYNCED_MS
    ▼ (requires LOGGED_LOCAL at minimum)
ARCHIVED (private copy verified: size + SHA-256, .nomedia present)
    ▼
PURGE_REQUESTED (MediaStore.createDeleteRequest → user consent)
    ▼
PURGED
```

**Invariants**
1. Purge never precedes a verified archive copy **and** a durable local log row.
2. Cloud sync failures never block archiving/purging — they retry from the queue.
3. LLM is only invoked post-gate (cost + privacy control).

## 3. Identity & Token Architecture

| | Google | Microsoft |
|---|---|---|
| Protocol | OIDC + OAuth2, auth-code + PKCE (AppAuth) | OIDC + OAuth2, auth-code + PKCE (MSAL or AppAuth) |
| Scopes | `openid email profile`, `https://www.googleapis.com/auth/drive.file` | `openid profile offline_access User.Read Files.ReadWrite` |
| Redirect | `com.shreddro.app:/oauth2redirect` (custom scheme) | `msauth://com.shreddro.app/<base64-sig-hash>` |
| Ledger access | Direct Sheets API on the central `Shreddro/Shreddro Transactions` (created by the app on link, so `drive.file` suffices); user-deployed Apps Script master sheet remains an opt-in legacy mode | Direct Graph workbook API on `Shreddro/Shreddro Transactions.xlsx`, created from a bundled empty workbook on link |
| Token storage | `EncryptedSharedPreferences` (AES-256, Keystore master key), per-provider namespaces | same |

Both providers can be linked simultaneously; `TransactionRepository` fans out to every *linked & enabled* target plus the mandatory local sink.

## 4. Sync Routing

```kotlin
enum class CloudProvider { GOOGLE, MICROSOFT, LOCAL_CSV }
```

`TransactionRepository.record(slip, image)`:
1. `LedgerSink.append(slip)` → local CSV/queue (never skipped — offline-first source of truth).
2. If Local Mode or no network → stop; queue marks pending cloud work.
3. For each linked provider: `SpreadsheetGateway.appendRow(slip)` and `BinaryStorageGateway.upload(image, slip.bankName)`; failures individually retried by WorkManager with backoff (queue drain worker).

## 5. Storage & Purge Design (Android specifics)

- **Read:** `READ_MEDIA_IMAGES` (API 33+) / `READ_EXTERNAL_STORAGE` (≤ 32). API 34+ partial access (`READ_MEDIA_VISUAL_USER_SELECTED`) honored.
- **Archive dir:** `context.getExternalFilesDir(null)/BankSlips_Archive/{bank}/{yyyy-MM}/` — app-scoped (no permission needed, invisible to MediaStore) + `.nomedia` written defensively at archive root.
- **Purge:** API 30+ `MediaStore.createDeleteRequest(resolver, uris)` → `IntentSenderRequest` → one system dialog for the whole batch. API 29: per-item `delete()` catching `RecoverableSecurityException`.
- **Atomicity:** copy → fsync → hash-verify → only then purge request. Temp file + rename for crash safety.

## 6. Backend: Apps Script Gateway (optional, legacy)

Not required since v0.6.0 — the app writes per-bank spreadsheets directly. Kept for users who prefer one master sheet they own end-to-end. Single `doPost(e)` Web App (execute-as: owner, access: anyone-with-link + shared-secret header check). Responsibilities: tab-per-bank creation, header bootstrap, `appendRow`, `LockService` for concurrent posts, JSON envelope response. Deployed by the *user* on their own account → their quota, their data.

## 7. Module / Package Map

```
core/src/main/kotlin/com/shreddro/core/
  model/    TransactionSlip.kt CloudProvider.kt SyncModels.kt
  parse/    SlipJsonParser.kt
  csv/      CsvFormatter.kt
  gateway/  Gateways.kt
  repo/     TransactionRepository.kt
core/src/test/kotlin/…                       (pure-JVM unit tests)
app/src/main/kotlin/com/shreddro/app/
  auth/     AuthConfig.kt AppAuthManager.kt
  storage/  StorageCoordinator.kt
  ocr/      MlKitSlipValidator.kt
  ai/       GeminiSlipParser.kt
  net/      AppsScriptApi.kt GraphApi.kt Clients.kt
  data/     AndroidCsvSink.kt
  work/     SlipScanWorker.kt
  MainActivity.kt
backend/apps-script/Code.gs
```

## 8. Technology Choices

| Concern | Choice | Rationale |
|---|---|---|
| Language | Kotlin 2.x, coroutines | KMP path |
| DI | Manual composition root (Phase 1) → Koin (KMP-friendly) later | avoid Hilt lock-in to Android |
| HTTP | Retrofit + OkHttp (app), interfaces in core | swap to Ktor client at KMP time |
| JSON | kotlinx.serialization | multiplatform |
| OCR gate | ML Kit Text (Thai) + Barcode | on-device, free, fast |
| LLM | Gemini 2.x Flash (default), pluggable | cost/latency |
| Background | WorkManager | Doze-safe retries |
| Persistence | CSV + JSON queue files Phase 1; Room later | minimal deps |

## 9. iOS Parity Notes (Phase 3)

- `MediaVault` port → PhotoKit adapter: `PHAsset` fetch by date, copy via `PHImageManager`, purge via `PHAssetChangeRequest.deleteAssets()` (system consent sheet — same UX shape as Android).
- No `.nomedia` concept needed — App Sandbox `Documents/BankSlips_Archive` is already outside the photo library.
- AppAuth-iOS for both providers; Keychain for tokens.
