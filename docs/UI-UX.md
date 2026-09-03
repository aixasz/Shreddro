# Shreddro — UI/UX Direction

**Design canvas (interactive mockups):** https://claude.ai/code/artifact/bae4f379-e47f-4e0b-83c9-645831204820
**Source artboards:** [`design/`](../design/) (`*.dc.html` + `canvas.json`)

## Research summary

- **Framework: Jetpack Compose + Material 3 Expressive.** Compose is the
  practical default for new Android UI, and M3 Expressive (Google's
  research-backed evolution of Material You — bolder type, rounder shapes,
  tonal surfaces, motion) is the current platform direction and what Android
  15/16 users see system-wide. Using the platform design system also lowers
  the bar for community contributors: standard components, no custom design
  system to learn. The `androidx.compose.material3` artifacts already in
  `app/build.gradle.kts` cover it.
- **Dynamic color** (Material You wallpaper theming) should be supported with
  the teal seed below as the brand fallback — one line with
  `dynamicColorScheme()` on Android 12+.
- **Thai-first typography:** Roboto Flex/Noto Sans Thai render Thai script
  well; body sizes stay ≥14sp because Thai glyphs carry stacked vowels/tone
  marks that get illegible smaller.

## Design principles (from the app's actual mechanics)

1. **One hero action per screen.** The core loop is *scan → results → sweep*.
   The sweep button is the emotional payoff (a cleaner gallery) and always the
   single filled-primary button; everything else is tonal/outlined/text.
2. **Trust before deletion.** The consent explainer ("a private copy is
   already archived — Android will ask you to confirm") sits directly above
   the sweep button, because the app's scariest moment is deleting photos.
   Never hide what sweeping does.
3. **Review is visible, never nagging.** Parked slips get a badge on the nav
   destination and a card on results — not blocking dialogs.
4. **Offline is a first-class citizen, not an error.** Local Mode and
   pending-sync states render as neutral chips ("pending ⟳"), not warnings.
5. **No fake device chrome** in mockups; real status bar/keyboard own that
   space.

## Screen inventory (matches the canvas)

| Screen | Purpose | Backed by |
|---|---|---|
| Onboarding | Value props + Google/Microsoft link + offline-only path; privacy note (no Shreddro servers) | `AppAuthManager`, `localModeForced` |
| Home | Ready-to-sweep hero count, month stats, sync chips, recent slips, bottom nav (Home/Ledger/Review+badge/Account) | registry + queue + CSV ledger |
| Scan results | Tiles (logged/review/skipped/failed), logged list, consent explainer, batched **Sweep** CTA | `PipelineOutcome` batch |
| Needs review | Cards with thumbnail, failure reason, Retry / Enter manually / Dismiss | `ReviewQueue`, `pipeline.retry/resolveManually` |

An alternate **dense ledger-first** wireframe direction sits on the canvas
below the main row for comparison — pick it if Shreddro should feel like a
power-user utility rather than a consumer app.

## Tokens (fallback palette when dynamic color is off)

- Seed / primary: `#006A60` (teal — trust + "clean") · on-primary `#FFFFFF`
- Primary container: `#A7F0E0` · on: `#00201B`
- Secondary container: `#DCE9E3` · surface: `#F4FAF7` · surface container: `#E8F0EC`
- Warning (review): container `#FFDF9E` · on: `#5C4300`
- On-surface: `#171D1B` · variant: `#3F4946`
- Shape: hero cards 28dp, cards 18–24dp, buttons full (stadium), chips 12dp
- Type: Roboto Flex — display 56/800 (hero count), title 20–22/700, body 14–15, label 11–12

## Implementation notes

- Keep `MainActivity` as the single activity; screens become Compose
  destinations (`androidx.navigation.compose`). The existing placeholder UI
  maps 1:1: scan button → Home hero, `ReviewSection` → Review screen,
  status text → Scan results screen.
- ViewModels stay thin: all state already lives in `:core`
  (`SyncQueue.pendingCount`, `ReviewQueue.list`, pipeline outcomes).
- Ledger tab (transaction history) reads the local CSV — no new storage.
