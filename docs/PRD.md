# Shreddro — Product Requirement Document (PRD)

**Version:** 1.0 · **Date:** 2026-09-03 · **Status:** Approved for Phase 1 implementation
**Platforms:** Android 10 (API 29) → Android 15+ (API 35) primary; iOS architectural parity planned.

---

## 1. Problem Statement

Thai mobile-banking users accumulate hundreds of transaction slip images (transfers via PromptPay, K PLUS, SCB Easy, Krungthai NEXT, Bangkok Bank, etc.) in their camera roll and screenshots folder. These slips:

1. **Clutter** the native gallery and cloud photo timelines (Google Photos / Apple Photos).
2. **Leak financial data** into broadly-synced photo libraries.
3. **Are never aggregated** — the transactional data inside them (amount, parties, reference) is trapped in pixels.

## 2. Product Vision

Shreddro automatically detects Thai bank slips, extracts their transaction data with AI, logs it into the user's own cloud spreadsheet, archives the raw image into an isolated private folder, and **scrubs the original from the system gallery** — leaving a clean camera roll and a structured personal ledger.

## 3. Target Users

- Primary: Thai consumers and micro-merchants who receive/send frequent PromptPay transfers and keep slips as proof.
- Secondary: freelancers/accountants reconciling personal transfers into spreadsheets.

## 4. Goals & Non-Goals

### Goals (Phase 1)
- G1: Detect bank-slip images in the device gallery (manual scan; auto-monitor behind a toggle).
- G2: Local pre-validation (Thai text + bank QR structure) before any cloud call — zero cost for non-slips.
- G3: AI parsing into a fixed JSON contract via a Vision LLM.
- G4: Append parsed rows to Google Sheets (via Apps Script gateway) **or** Excel Online (via Microsoft Graph), partitioned per bank.
- G5: Upload the raw image to Google Drive / OneDrive under `/…/Bank Slips/{BankName}/`.
- G6: Offline / Local-Only mode → append to a sandboxed CSV; queue cloud sync.
- G7: Archive image into a private non-media directory (`.nomedia`) and purge the original via the platform delete consent dialog.
- G8: OIDC sign-in with Google and Microsoft; either or both may be linked concurrently.

### Non-Goals (Phase 1)
- No iOS build (architecture must permit it; see Blueprint).
- No slip *verification* against bank APIs (authenticity checking).
- No multi-user / team sharing.
- No editing of historical spreadsheet rows from the app.

## 5. User Stories

| ID | Story | Priority |
|----|-------|----------|
| US-1 | As a user, I sign in with my Google or Microsoft account so my data syncs to my own cloud. | P0 |
| US-2 | As a user, I tap **Scan Gallery** and Shreddro finds new bank slips since the last scan. | P0 |
| US-3 | As a user, non-slip photos are never uploaded anywhere. | P0 |
| US-4 | As a user, each slip becomes a row (bank, date/time, amount, sender, receiver, ref) in a per-bank sheet tab. | P0 |
| US-5 | As a user, the raw slip image lands in a dedicated cloud folder per bank. | P1 |
| US-6 | As a user, after processing, the slip disappears from my gallery (with a one-tap system consent), and a private archive copy is kept on-device. | P0 |
| US-7 | As an offline user, slips log into a local CSV and sync later. | P1 |
| US-8 | As a user, I can toggle **Local Mode** to keep everything on-device. | P1 |
| US-9 | As a user, I get a notification summarizing each batch (N processed, N purged, N failed). | P2 |

## 6. Functional Requirements

### FR-1 Identity (OAuth 2.0 / OIDC)
- Providers: **Google** (scopes: `openid email profile`, `https://www.googleapis.com/auth/drive.file`, Apps Script Web App executes as the user's deployment — no Sheets scope needed on-device) and **Microsoft** (scopes: `openid profile offline_access User.Read Files.ReadWrite`).
- AppAuth-style authorization-code flow with PKCE; refresh tokens stored in `EncryptedSharedPreferences` (Android Keystore-backed).
- Both providers may be linked at once; sync engines run concurrently.

### FR-2 Capture & Interception
- Manual scan of `MediaStore.Images` filtered by bucket (Camera, Screenshots) and `DATE_ADDED > lastScanCursor`.
- Optional background monitor: `WorkManager` periodic job + `ContentObserver` while app is foregrounded. (No abuse of foreground services; complies with Play policy.)

### FR-3 Local Extraction Check (Gate)
- ML Kit Text Recognition (Thai script model) — require ≥ N Thai characters and slip-keyword heuristics (e.g., "โอนเงิน", "จำนวนเงิน", "บาท", "สำเร็จ").
- ML Kit Barcode Scanning — detect QR payload matching EMVCo / Thai bank verification structure (TLV starting `00…`, or bank deep-link URLs).
- Gate passes only if (Thai text ∧ keywords) ∨ (Thai text ∧ bank QR). Failures are skipped and remembered (hash) to avoid rescans.

### FR-4 AI Parsing
- Provider-pluggable Vision LLM (default: Gemini Flash; alternate: GPT-4o-mini). Image sent as base64 with a strict-JSON system prompt.
- **Data contract (exact):**
  ```json
  {
    "bank_name": "string",
    "date_time": "string",
    "amount": 0.0,
    "sender": "string",
    "receiver": "string",
    "reference_id": "string"
  }
  ```
- Responses failing schema validation are retried once with a repair prompt, then routed to a "NeedsReview" local queue.

### FR-5 Spreadsheet Integration
- **Google:** POST to an Apps Script Web App URL. Script owns the spreadsheet ID, creates a tab per `bank_name` (headers on first insert), appends the row, returns `{ "status": "ok", "sheet": "...", "row": n }`.
- **Microsoft:** Graph `POST /me/drive/items/{itemId}/workbook/tables/{tableName}/rows/add` with `values: [[…]]`; one table per bank (auto-created via `worksheets/add` + `tables/add` when missing).

### FR-6 Binary Cloud Sync
- Google Drive: `drive.file` scope, folder path `My Bank Slips/{BankName}/`, resumable upload.
- OneDrive: `PUT /me/drive/root:/Documents/Bank Slips/{BankName}/{fileName}:/content` (< 4 MB simple upload; upload session otherwise).

### FR-7 Offline-First / Local Mode
- All pipeline outputs go through a local Room/queue first (source of truth), then sync workers drain it.
- Unauthenticated / offline / Local-Mode → append to `files/ledger/transactions.csv` (RFC 4180, UTF-8 BOM for Excel-Thai compatibility), thread-safe, atomic line appends.

### FR-8 Media Scrubbing & Gallery Purge
1. Copy original bytes → `getExternalFilesDir(null)/BankSlips_Archive/{BankName}/{yyyy-MM}/file.jpg` (or internal `filesDir` for maximal isolation).
2. Ensure `.nomedia` exists at archive root (defense-in-depth; app-specific dirs are already scanner-exempt).
3. Verify copy integrity (size + SHA-256).
4. Request deletion via `MediaStore.createDeleteRequest()` (API 30+) → single system consent dialog, batchable. API 29 fallback: `ContentResolver.delete` + `RecoverableSecurityException` → `IntentSender`.
5. Delete is **never** attempted before copy verification succeeds (atomicity invariant).

## 7. Non-Functional Requirements

- **Privacy:** images leave the device only to the user's own chosen LLM provider + their own Drive/OneDrive; nothing to Shreddro servers (there are none in Phase 1 beyond the user-deployed Apps Script).
- **Security:** tokens in Keystore-encrypted prefs; TLS everywhere; no image persisted in LLM logs (use provider no-retention settings where available).
- **Reliability:** pipeline steps idempotent & resumable; WorkManager with exponential backoff; local queue survives process death.
- **Performance:** local gate < 300 ms/image on mid-range devices; batch of 20 slips end-to-end < 60 s on Wi-Fi.
- **Compatibility:** Android 10–15+, scoped storage only (`requestLegacyExternalStorage=false`).
- **Cost:** LLM called only for gate-passed images; target < $0.001/slip with Flash-class models.

## 8. Success Metrics

- ≥ 95 % of true slips pass the local gate; ≤ 2 % false-positive gate rate.
- ≥ 98 % schema-valid LLM parses without repair retry.
- 0 data-loss incidents (row logged before original purged — enforced by state machine).
- Median gallery-clean latency (capture → purged) < 5 min with monitor on.

## 9. Phasing

- **Phase 1 (this delivery):** Android core pipeline, Google + Microsoft sync engines, local CSV fallback, StorageCoordinator purge flow, Apps Script gateway.
- **Phase 2:** Background auto-monitor polish, NeedsReview UI, Drive/OneDrive binary sync workers, notification digests.
- **Phase 3:** KMP extraction of `core` module; iOS app (PhotoKit + `PHAssetChangeRequest.deleteAssets()`).

## 10. Open Questions / Risks

| Risk | Mitigation |
|------|-----------|
| Play Store sensitive-permission review for gallery deletion UX | Deletion is user-consented per batch via the OS dialog; document in Data Safety form. |
| LLM misreads amounts (OCR of Thai numerals/fonts) | Cross-check LLM amount against ML Kit raw text when both available; flag mismatch → NeedsReview. |
| Apps Script quota (20k URL-fetch/day, 6 min exec) | Rows are tiny; batch endpoint provided; Graph path unaffected. |
| Google Photos may already have cloud-backed the image before purge | Documented limitation; purge propagates deletion to Google Photos trash via MediaStore removal + user's Photos sync. |
