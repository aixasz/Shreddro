# Shreddro launcher icon — concept directions

Brand constraints: primary teal `#006A60`, white glyph, Material 3 Expressive,
"slip being shredded" motif from the in-app header glyph
(`design/Main.dc.html`, `design/Onboarding.dc.html`).

## Concept A — Shredded Slip (chosen)

Direct evolution of the in-app glyph: an outlined receipt slip with three
rounded shred strips of differing lengths falling below it. Familiar from the
app UI, reads instantly at 48px, and the uneven strip lengths give it the
playful "mid-shred" motion.

```svg
<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96" viewBox="0 0 108 108">
  <rect width="108" height="108" rx="24" fill="#006A60"/>
  <g fill="#FFFFFF">
    <path fill-rule="evenodd" d="M43,36 L65,36 A5,5 0 0 1 70,41 L70,47 A5,5 0 0 1 65,52 L43,52 A5,5 0 0 1 38,47 L38,41 A5,5 0 0 1 43,36 Z M44,40 L64,40 A2,2 0 0 1 66,42 L66,46 A2,2 0 0 1 64,48 L44,48 A2,2 0 0 1 42,46 L42,42 A2,2 0 0 1 44,40 Z"/>
    <path d="M41,58 A2,2 0 0 1 45,58 L45,70 A2,2 0 0 1 41,70 Z"/>
    <path d="M52,58 A2,2 0 0 1 56,58 L56,65 A2,2 0 0 1 52,65 Z"/>
    <path d="M63,58 A2,2 0 0 1 67,58 L67,72 A2,2 0 0 1 63,72 Z"/>
  </g>
</svg>
```

## Concept B — Shredder Slot

A horizontal shredder-mouth bar with the top half of a slip poking out above
it and strips emerging below. More literal "machine" storytelling, but the
slot bar adds a heavy horizontal mass that crowds the safe zone and the slip
loses its receipt identity when cropped at small sizes.

```svg
<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96" viewBox="0 0 108 108">
  <rect width="108" height="108" rx="24" fill="#006A60"/>
  <g fill="#FFFFFF">
    <path fill-rule="evenodd" d="M45,32 L63,32 A4,4 0 0 1 67,36 L67,48 L41,48 L41,36 A4,4 0 0 1 45,32 Z M45,36 L45,44 L63,44 L63,36 Z"/>
    <path d="M36,48 L72,48 A4,4 0 0 1 76,52 A4,4 0 0 1 72,56 L36,56 A4,4 0 0 1 32,52 A4,4 0 0 1 36,48 Z"/>
    <path d="M43,62 A2,2 0 0 1 47,62 L47,72 A2,2 0 0 1 43,72 Z"/>
    <path d="M52,62 A2,2 0 0 1 56,62 L56,68 A2,2 0 0 1 52,68 Z"/>
    <path d="M61,62 A2,2 0 0 1 65,62 L65,74 A2,2 0 0 1 61,74 Z"/>
  </g>
</svg>
```

## Concept C — Logged, Then Shredded

The slip carries a checkmark (recorded to the ledger) with a single shred
strip below — tells the full product story (verify, log, sweep). But two ideas
in one mark: the check competes with the shred motif and turns to noise below
64px, and it drifts from the established in-app glyph.

```svg
<svg xmlns="http://www.w3.org/2000/svg" width="96" height="96" viewBox="0 0 108 108">
  <rect width="108" height="108" rx="24" fill="#006A60"/>
  <g fill="none" stroke="#FFFFFF" stroke-width="4" stroke-linecap="round" stroke-linejoin="round">
    <rect x="38" y="34" width="32" height="22" rx="5"/>
    <path d="M48,45 L52,49 L60,41"/>
    <line x1="46" y1="62" x2="46" y2="72"/>
    <line x1="54" y1="62" x2="54" y2="67"/>
    <line x1="62" y1="62" x2="62" y2="74"/>
  </g>
</svg>
```

## Decision

**Concept A.** It is the icon-scale version of the glyph users already see in
the app header and onboarding, so brand recognition is free; it stays fully
inside the 66dp adaptive-icon safe zone with a single clear silhouette; and
the three uneven strips carry the playful "shred" personality without extra
elements. Production files: `app/src/main/res/drawable/ic_launcher_foreground.xml`
(filled paths, no strokes) and `icon-preview.svg` (512x512) in this folder.
