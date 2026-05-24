---
name: ripdpi-design
description: Use this skill to generate well-branded interfaces and assets for RIPDPI (an Android-native, Compose-first VPN and DPI-bypass app), either for production or throwaway prototypes/mocks/etc. Contains essential design guidelines, colors, type, fonts, brand-ship icons, and an interactive UI kit of every public composable from the live ui/components tree.
user-invocable: true
---

# RIPDPI Design System skill

Read `README.md` in this skill first — it covers the register, content
fundamentals, visual foundations, and iconography that the rest depends on.
Then open the other files as you need them.

## Where things live

- `README.md` — the contract. Read first.
- `colors_and_type.css` — every token as a CSS custom property + `@font-face`
  declarations for Geist Sans / Mono / Pixel Circle. Use this directly in
  HTML artifacts.
- `fonts/` — TTF files referenced by `colors_and_type.css`.
- `assets/icons/` — the 6 brand-ship outline icons (SVG, 24×24) and the 6
  brand-mark variants (clean / cracked / disintegrate / glitch / rubble /
  stitch).
- `assets/raw/` — Android vector drawable XML originals (kept for parity).
- `preview/` — atomic design-system cards. Useful as references but not
  meant to be linked from production HTML.
- `ui_kits/android/` — the production UI kit. JSX recreations of every
  public composable, wired into a 6-screen click-through. Read
  `RipDpiComponents.jsx` to see the API; read `index.html` to see screens
  composed.

## How to do work with this skill

### For visual artifacts (slides, mocks, throwaway prototypes)

1. Copy `colors_and_type.css` + `fonts/` + any icons you need out of this
   skill into the artifact folder.
2. Link `colors_and_type.css` from your HTML and consume the tokens via
   `var(--rdp-…)`. Don't inline raw hex, raw px, or new font sizes.
3. If you need component visuals, copy `ui_kits/android/RipDpiComponents.jsx`
   into the artifact and import it. It self-contains everything: a theme
   provider, every Compose-analog component, and the `RDP` token bundle.
4. **The register is engineering-tool, not consumer-brand.** No gradients,
   no illustrations, no emoji, no decorative copy, no marketing voice. If
   you reach for any of those, you're working against the brand.

### For production code

1. Read `README.md` for the rules.
2. Treat the live Kotlin sources at `app/src/main/kotlin/com/poyka/ripdpi/ui/theme/`
   (in the user's codebase, not this skill) as authoritative. The CSS in
   this skill is a mirror, not the contract.
3. Extend by adding fields to the matching token data class
   (`RipDpiExtendedColors`, `RipDpiTextStyles`, `RipDpiShapeMetrics`,
   `RipDpi*Metrics`, `RipDpi*StateTokens`, `RipDpiSurfaceRoleMappings`),
   never by inlining a raw value.

## When invoked without other guidance

Ask the user what they want to build or design. Useful questions:

- Is this a mock / prototype / production code? (Determines fidelity rules.)
- Which screen or surface? (Home, Diagnostics, Strategy config, Logs,
  Onboarding, or a new screen?)
- Light, dark, or both? (Both are first-class; the kit ships both.)
- Compact, Medium, or Expanded width class? (Default is Compact.)
- Does it need to compose existing `RipDpi*` components, or is this a new
  component that needs to plug into `RipDpiThemeTokens`?

Then act as an expert designer who outputs HTML artifacts _or_ production
Compose code, depending on the need.

## Hard rules (cribbed from README.md)

- 48dp touch-target floor. Non-negotiable.
- No drop shadows — depth via 1dp border or 0.5dp hairline (#666).
- Mono is load-bearing — every machine-readable value (IPs, hex, byte
  counts, durations, log lines) renders in Geist Mono. Never sans.
- Strings are imperative, sentence case, no second person, no apology, no
  marketing voice. `Connect` not `Tap to connect now`.
- No emoji. No unicode-character icons. Sentinel shapes (filled circle,
  diamond, triangle, square) signal status tone.
- No Material You / dynamic color. Brand identity overrides system accent.
- No tertiary accent colors. Color = semantic state only.
