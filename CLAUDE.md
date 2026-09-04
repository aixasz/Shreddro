# Shreddro — Project Constitution (AI Context)

Rules and decisions that govern this repo. AI assistants and contributors
must follow these; change them only with the owner's (Aixasz) explicit call.

## Branch strategy (MANDATORY)

```text
feature/* ──merge──► dev ──cut──► release/vX.Y.Z ──CI──► main
```

- **`dev`** is the integration branch. Feature branches merge into `dev`.
- **Bug fixes** are verified locally first: `:core` unit tests + USB remote
  debugging on a real device. Ordinary commits do NOT trigger CI.
- **CI runs only when a `release/vX.Y.Z` branch is pushed** (cut from `dev`).
  The workflow (.github/workflows/android.yml) runs tests, builds signed
  APKs, publishes the tagged GitHub Release, and — on success — **merges the
  release branch into `main` automatically**.
- **`main` only ever contains released, CI-verified code.** Never commit or
  merge to `main` by hand; never push tags by hand (CI tags from the branch
  name).
- To ship: `git checkout dev && git checkout -b release/vX.Y.Z && git push -u
  origin release/vX.Y.Z` — then let CI finish.

## Non-negotiable product decisions

- **Parsing is 100% on-device** (Tesseract tha+eng + template rules + QR).
  No cloud LLM / AI API may be reintroduced without the owner's explicit ask.
- **Purge safety invariant:** a gallery original is deleted only after the
  transaction row is durably logged locally AND the archive copy is
  hash-verified against the bytes the app wrote (and decode-checked when it
  is the downsized JPEG). Since v0.7.0 the archive keeps the same ≤1600 px
  JPEG the cloud gets by default (owner's call: offline baseline must be
  small); "Compress archive on this phone" off keeps byte-exact originals.
  Cloud sync failures must never block or trigger deletion.
- **Offline-first:** the local CSV ledger is the source of truth; cloud ops
  queue durably and drain in the background when connectivity returns.
- **No secrets in the APK or repo.** OAuth client ids live in
  local.properties / CI secrets; user credentials (Apps Script URL/secret)
  are runtime in-app settings in encrypted prefs.
- `:core` stays pure Kotlin — zero `android.*` imports — for the planned KMP
  extraction (iOS via PhotoKit later). Platform code goes in `:app` behind
  the `gateway` ports.

## Practical notes

- Publisher/brand name: **Aixasz**. Package: `com.shreddro.app`.
- Build: Android Studio or JDK 17 + Gradle 8.7 (no wrapper committed);
  `./gradlew :core:test` must pass before any release branch is cut.
- Real-slip OCR fixtures live in `core/src/test/resources/ocr-dumps/`
  (owner-provided KBank / Krungthai / Bangkok Bank slips). New bank support
  requires a fixture + assertions in `RealOcrDumpTest`.
- Release signing: keystore is NOT in the repo; CI signs via secrets. The
  release keystore's SHA-1 must be registered with the Google OAuth client
  and the Entra app before OAuth works on release builds.
- Docs: PRD (docs/PRD.md), architecture (docs/ARCHITECTURE.md), UI/UX +
  design canvas (docs/UI-UX.md), OCR ground truth (docs/SLIP-SAMPLES.md).
