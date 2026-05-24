# RIPDPI Design System (RDS)

> **Engineering-tool register, not consumer-brand register.** RDS is the
> token-and-component system behind RIPDPI, an Android-native, Compose-first
> app for privacy-focused VPN and DPI-bypass with packet-level configuration
> and network telemetry. The visual language reads closer to a network
> analyzer or developer console than a streaming app — calm, precise,
> near-monochrome, slightly utilitarian.

## What this is

RDS is built on Material 3 Expressive and extended with engineering-grade
**token layers** (color, type, spacing, shape, motion, layout, component,
state, surface) tuned for dense diagnostics, network telemetry, packet-level
configuration, and high-stakes connection controls.

| Surface today | Shape | Notes |
| --- | --- | --- |
| Android phone (8.0+) | ~7,000 LOC of composables, 60+ public components | Primary surface |
| Glance home-screen widget | Parallel theme tree `RipDpiGlanceTheme` / `RipDpiGlanceColors` | Token names kept in sync with Compose theme |
| _Future_ — Wear OS, KMP docs, Figma library, marketing site | Same semantic tokens, thin platform bridge | Token shape is deliberately platform-agnostic |

Localized into 7 languages: `en`, `ru`, `es`, `de`, `fr`, `fa`, `zh-CN`.
`lint.xml` sets `MissingTranslation severity="error"` — every new string
ships into all locales in the same commit.

## Sources used to build this design system

| Source | Where | Notes |
| --- | --- | --- |
| **Codebase (Compose UI tree)** | `ui/` mount via the project file system, paths `ui/components/…`, `ui/screens/…`, `ui/theme/…` | Primary source of truth — read these directly |
| **GitHub repo** | <https://github.com/po4yka/RIPDPI> | Browse `app/`, `core/`, `docs/`, `DESIGN.md`, `README.md`, locale README-{ru,es,de,fr,zh-CN}.md |
| **Brand & icon assets** | Uploaded TTF + Android vector drawables, copied into `fonts/` and `assets/` of this project | Includes 6 launcher-foreground variants of the brand mark |

To extend the system or rebuild fidelity, explore the GitHub repo above
directly — `DESIGN.md` and the `ui/theme/` Kotlin files are the formal
contract. `app/src/test/kotlin/com/poyka/ripdpi/ui/theme/` (the
`RipDpiColorContrastTest`, `RipDpiStateTokensTest`, `RipDpiSurfaceTokensTest`)
encode the rules in code.

---

## Index — what's in this folder

```
README.md                        ← you are here
SKILL.md                         ← agent skill manifest (Claude Code compatible)
colors_and_type.css              ← all tokens as CSS custom properties + @font-face

fonts/                           ← Geist Sans, Geist Mono, Geist Pixel Circle
assets/
  ic_launcher.png                ← brand mark (raster)
  icons/                         ← Android vector drawables converted to SVG
    ic_launcher_foreground_ripdpi_clean.svg        ← canonical brand mark
    ic_launcher_foreground_ripdpi_cracked.svg      ← variant: "cracked"
    ic_launcher_foreground_ripdpi_disintegrate.svg ← variant: "disintegrate"
    ic_launcher_foreground_ripdpi_glitch.svg       ← variant: "glitch"
    ic_launcher_foreground_ripdpi_rubble.svg       ← variant: "rubble"
    ic_launcher_foreground_ripdpi_stitch.svg       ← variant: "stitch"
    ic_launcher_monochrome_ripdpi.svg              ← themed icon (Android 13+)
    ic_settings_outline.svg
    ic_tune_outline.svg
    ic_stop_outline.svg
    ic_vpn_key_outline.svg
    ic_article_outline.svg
    ic_notification.svg
  raw/                           ← Android vector drawable XML sources (kept for parity)

preview/                         ← Design System cards — one foundational concept each
ui_kits/
  android/                       ← Android phone UI kit (Compose components recreated in JSX)
    README.md
    index.html                   ← interactive click-through prototype
    *.jsx                        ← composable analogs: button, card, switch, chip, etc.
```

## Index — reading order for an agent or designer new to the system

1. This file (`README.md`) — for the register, the don'ts, the voice.
2. `colors_and_type.css` — for every token you'll consume.
3. `ui_kits/android/index.html` — to see the tokens composed into screens.
4. The `preview/*.html` cards — atomic spec sheets for each token cluster.
5. The Kotlin source under `ui/theme/` (in the mounted codebase) — the formal
   contract; tokens are immutable data classes, never raw constants.

---

## Content fundamentals

The voice is **precise, technical, and calm**. Strings are imperative and use
product-domain vocabulary without softening or analogizing. Decorative copy
is forbidden by design — there is no "marketing pass" later; the placeholder
copy that ships in screenshots **is** the production copy.

### Casing & person
- **Sentence case everywhere.** `Connect`, `Reset cache`, `Open diagnostics`.
  Not Title Case, not ALL CAPS. The one exception: section headers use the
  `sectionTitle` style which is rendered uppercase-feeling via tracked
  letter-spacing (0.72px) but the source string is still sentence case.
- **No second person.** Strings address the system, not the user. `Apply
  strategy`, not `Apply your strategy`. Empty states explain what the screen
  _does_, not what the user _wants_.
- **No first person.** No "we", no "let's", no "you'll need to".

### Vocabulary (use these, don't dilute them)
`probe`, `strategy`, `relay`, `transport`, `DPI`, `fingerprint`, `fragment`,
`route`, `tunnel`, `MTU`, `desync`, `host fake`, `seq overlap`, `TLS prelude`,
`pluggable transport`, `whitelist`, `bypass`, `actuator`, `pcap`.

### Numbers carry units; units never translate
`128 KiB`, `12 ms`, `1500 MTU`, `00:18:42`, `127.0.0.1:1080`. In `monoValue`
style — never sans, never localized.

### Empty states
One sentence describing what the screen is for, then the single most likely
next action.
> _Examples (from the codebase):_
> - **No analysis yet** → primary action: `Run scan`.
> - **No saved presets** → primary action: `Save current strategy`.

### Error states
Name what failed in technical terms, offer remediation, never apologize.
> _Examples:_
> - **`Failed to start VPN`** + secondary line with cause + action `Retry` /
>   `Open VPN permission`.
> - **`Validation warning — Double-check the current values before saving the
>   preset.`** — warning banner tone.
> - **`Manual step required — VPN permission must be granted before the
>   service can start.`** — info banner tone.
> - **`Feature unavailable — This control only applies when command-line
>   mode is enabled.`** — restricted banner tone.

### Buttons are imperative verbs
`Connect`, `Disconnect`, `Disable`, `Enable`, `Run scan`, `Configure`,
`Reset cache`, `Reload strategy`, `Apply`, `Retry`, `Cancel`, `Save`, `Open
diagnostics`, `Reset to defaults`. One- or two-word labels. Never a
sentence, never marketing voice.

### Status labels are short and technical
`Connected 00:18:42`, `Connecting`, `Disconnected`, `Inactive`,
`Stage 2 of 4 — Testing TCP`, `Running`, `Idle`, `Warning`, `Error`.

### Emoji
**Not used.** Anywhere. The brand-mark variants and monoglyph status
indicators are the only "icon-like" decorations on a surface. Unicode glyphs
are not used as icons either. Sentinel state uses small shape primitives
(circle, triangle, square, diamond) drawn on a `Canvas`, not characters.

### Vibe in one line
A senior network engineer at 11 pm reading a packet capture. Calm. Precise.
No marketing flourish. No apology. The next correct action is one
imperative button away.

---

## Visual foundations

### Color
The palette is intentionally narrow and almost entirely **neutral**. Color
is reserved for semantic state and is **never used decoratively**.

- **Light mode anchor** — `#FAFAFA` background, `#1A1A1A` foreground.
- **Dark mode anchor** — `#121212` background (never pure black — avoids
  OLED smearing), `#E8E8E8` foreground.
- **Semantic states** ship in 4 variants each (base, foreground, container,
  container-foreground) across both modes:
  - `success` emerald — `#047857` / `#34D399`
  - `warning` amber — `#B45309` / `#FBBF24`
  - `destructive` red — `#B91C1C` / `#F87171`
  - `info` blue — `#1D4ED8` / `#60A5FA`
  - `restricted` slate — `#6B7280` / `#9CA3AF` (root-only / capability-gated)
- **Three contrast levels** — Standard / Medium / High blend
  `mutedForeground`, `border`, `cardBorder`, `outlineVariant` toward
  `foreground` at fixed ratios. WCAG AA across all three is enforced by
  `RipDpiColorContrastTest`.
- **Not Material You.** Brand identity overrides the system accent, and
  dynamic color is **disabled**.
- **No tertiary accent colors.** No decorative purple, no on-trend gradient,
  no third "brand" hue.

### Typography
Three families, no exceptions:
- **Geist Sans** — every UI string.
- **Geist Mono** — every value that represents engineering data (IPs, hex,
  byte counts, packet traces, configuration keys, log lines, latency,
  durations). Mono **signals to the user that a value is machine-readable
  and copy-pasteable**.
- **Geist Pixel Circle** — brand mark only (`brandMark` style, 32/48).

`PlatformTextStyle(includeFontPadding = false)` is set globally so vertical
rhythm is honored. The type scale is small and shallow — extend
`RipDpiTextStyles` rather than introducing new sizes.

### Spacing
Closed 9-step scale (dp) exposed via `RipDpiSpacing.xs … screen`:
**4 / 8 / 12 / 16 / 20 / 24 / 32 / 40 / 48**.

Three width classes at breakpoints `600dp` and `840dp` (Compact / Medium /
Expanded). Each supplies a complete `RipDpiLayout` block — horizontal
padding (20/28/32), content max-width (560/720/960), form max-width
(520/600/680), card padding (16/18/20), section/group gap, app-bar
heights. Forms are **width-capped** on wide screens — content centers, it
never stretches edge-to-edge.

### Touch targets
**48dp floor everywhere.** Button minHeight, iconButton size, control
height, switch height, settings-row min-height — non-negotiable across all
widths.

### Backgrounds
- No images.
- No gradients.
- No illustration.
- No repeating patterns or textures.
- No skeuomorphism.
- Just `--rdp-background` and `--rdp-card`. Variety comes from semantic
  containers (`*-container` colors) used by banners and chips at their
  correct contrast level.

### Depth, elevation, and shadows
The system **avoids shadows almost entirely**. Depth is communicated with:
- **1dp border** (`RipDpiStroke.Thin`) — outlined cards, primary edges
- **0.5dp hairline** (`RipDpiStroke.Hairline` on `#666666`) — fine
  separators, dense list rows
- **`cardBorder` / `divider` / `outlineVariant`** tokens — every other edge
- A **subtle surface tint** via `RipDpiSurfaceTokens` — never a drop shadow

Material 3 elevation surfaces are mapped through explicit surface roles
(`card`, `cardElevated`, `bannerInfo`, etc.) so a future redesign that
introduces shadow or blur can be done at the **token** level without
touching components.

### Corner radii (closed scale, dp)
| Token | dp | Used for |
| --- | --- | --- |
| `xs` | 4 | Tiny inline tags |
| `sm` | 8 | Sub-card surfaces |
| `md` | 10 | Inline tonal blocks |
| `lg` (chip) | 12 | Chips |
| `xl` (control / card) | 16 | Buttons, text fields, cards |
| `xl-increased` | 20 | Pressed-state grown chip |
| `xxl` (pill) | 28 | Pill / actuator carriage |
| `xxl-increased` | 32 | Hero pill |
| `xxxl` (hero) | 48 | Connection actuator surround |
| `full` | `CircleShape` | Brand badge, status pulse, dismiss icon |

### Animation
Motion is a first-class token layer (`RipDpiMotion`), **not a per-component
decision**. Vocabulary is small and named:

| Token | Duration | Use |
| --- | --- | --- |
| `quick` | 120ms | State changes, taps, scale on press |
| `state` | 220ms | Color / opacity transitions |
| `emphasized` | 320ms | Section expansion |
| `route` | 260ms | Navigation transitions |

Easings (Material 3 Expressive):
- `EmphasizedDecelerate` — `cubic-bezier(0.05, 0.7, 0.1, 1.0)` — entering
- `EmphasizedAccelerate` — `cubic-bezier(0.3, 0.0, 0.8, 0.15)` — exiting
- `StandardEasing` — `cubic-bezier(0.2, 0.0, 0.0, 1.0)` — in-place

Springs come in two variants:
- **Standard** — damping 1.0, stiffness 500 — critically damped, no
  overshoot. Used for press/release.
- **Expressive** — damping 0.7, stiffness 400 — slight bounce for selection
  emphasis.

`reducedMotion` collapses durations to a minimum of 80ms and substitutes
critically-damped springs everywhere. `animationsEnabled = false` returns
`EnterTransition.None` / `ExitTransition.None` so previews and inspection
mode are deterministic.

### Hover, press, focus
- **Hover** — not applicable on touch surfaces. Pointer-input surfaces (Wear
  OS or web) use a subtle `--rdp-accent` background hint.
- **Press** — `pressScale = 0.98` (scale down), critically-damped spring.
  No color change beyond the resolved press state in the relevant
  `RipDpi*StateTokens` data object. **Never** an ad-hoc darkening or
  lightening.
- **Selection emphasis** — `selectionScale = 1.02`, expressive spring.
- **Focus** — visible focus border thickens or grows the corner radius
  rather than introducing a glow. Density compensates with
  `focusedHorizontalPaddingOffset = 4dp` so the layout doesn't shift.

### Transparency and blur
Used sparingly:
- **Scrim** behind modal sheets and dialogs (`#000000 60%` light /
  `#000000 80%` dark).
- **`contentAlpha`** on disabled buttons / cards (`0.38` global disabled
  alpha).
- **No background blur**, no glassmorphism, no frosted panels. They
  contradict the "engineering tool" register.

### Imagery
There is no product imagery. The only raster asset is the launcher icon
(`assets/ic_launcher.png`). All other visual signal comes from type,
mono-glyph status markers, and the 6 launcher-foreground brand-mark variants
(clean, cracked, disintegrate, glitch, rubble, stitch).

### Cards
| | |
| --- | --- |
| Background | `--rdp-card` (`#FFFFFF` / `#1A1A1A`) |
| Border | `--rdp-card-border` (`#E8E8E8` / `#2A2A2A`), 1dp |
| Border radius | `--rdp-radius-xl` (16dp) |
| Padding | `--rdp-card-padding` (16/18/20 by width class) |
| Shadow | **None** (this is the system rule) |
| Inner spacing | `--rdp-space-sm` (8dp) between rows |

There are 4 variants — `Outlined` (default), `Tonal` (active state, tinted
muted background), `Elevated` (slightly higher-priority surface, still no
shadow, just a brighter border), `Status` (semantic-container background).

---

## Iconography

### What ships with the brand
The product's own iconography is **outline Material-style** drawn on a
**24×24 viewport**, all stroke 2 (within the Material guidelines), tinted
to `--rdp-foreground` by default and to the resolved state color when used
inside a `WarningBanner` or `StatusIndicator`.

In this folder you have:
- **`assets/icons/ic_settings_outline.svg`** — settings gear (Material
  spec)
- **`assets/icons/ic_tune_outline.svg`** — advanced settings dial
- **`assets/icons/ic_stop_outline.svg`** — stop / pause connection
- **`assets/icons/ic_vpn_key_outline.svg`** — VPN config
- **`assets/icons/ic_article_outline.svg`** — logs / documentation
- **`assets/icons/ic_notification.svg`** — system notification icon

These were copied from `app/src/main/res/drawable/` and converted from
Android vector drawables to SVG (same path data — no re-drawing). They are
the **brand-ship icons** for the surfaces they appear on; everything else
in the live app draws from **`androidx.compose.material.icons`** (Material
Symbols) via the central `RipDpiIcons` accessor at `ui/theme/RipDpiIcons.kt`
in the codebase.

### Icon strategy for new HTML mocks
1. **Prefer the bundled SVGs** in `assets/icons/`. They are the brand-ship
   set.
2. For Material Symbols (Settings, Lock, Warning, Info, Close, Check,
   ChevronRight, Refresh, etc.), use them at the **same 24×24 outline
   style, weight 400, stroke 2** — link Google's Material Symbols CSS
   stylesheet (`outlined`, weight 400) from a CDN, or copy the specific
   ones in.
3. **Substitution flag** — if you reach for any non-Material icon set
   (Lucide, Heroicons, Phosphor), call it out in your output. The brand
   contract is Material outline; deviations are a deliberate departure.

### Brand mark variants
Six glyph variants of the RIPDPI brand mark exist as launcher-foreground
vector drawables — `clean`, `cracked`, `disintegrate`, `glitch`, `rubble`,
`stitch`. They are used **only on the launcher icon, splash, and onboarding
surfaces**. Default to `clean` everywhere else. The other five exist to
imply the product's purpose (disrupting deep-packet inspection) without
animating or decorating other surfaces.

### Pixel font
`Geist Pixel Circle` (`brandMark` style, 32/48 with `letter-spacing 0.8px`)
is the **only** decorative font in the system. It is used **for the brand
mark only** — typically rendering "RIPDPI" in the top app bar. Never use it
for body text, headings, or marketing copy. Never use it for run-of-the-mill
labels.

### Emoji
**Not used.** Anywhere.

### Unicode characters as icons
**Not used.** Sentinel shapes (idle = diamond, active = filled circle,
warning = triangle, error = square) are drawn on `Canvas` in the live app,
or as inline `<svg>` in HTML. See `StatusIndicator.kt`.

---

## Do's and don'ts

**Do**
- Extend the system by **adding fields to the matching token data class**
  (`RipDpiExtendedColors`, `RipDpiTextStyles`, `RipDpiShapeMetrics`,
  `RipDpi*Metrics`, `RipDpi*StateTokens`, `RipDpiSurfaceRoleMappings`) and
  reading them via `RipDpiThemeTokens`.
- Go through `RipDpiMotion.duration()` for **every** animation so that
  `reducedMotion` and `ValueAnimator.areAnimatorsEnabled()` are honored
  automatically.
- Localize every string into all 7 supported locales **in the same commit**.
- Expose proper semantics on bespoke controls (the connect actuator
  declares `role = Switch` with a `stateDescription`).

**Don't**
- Introduce Material You / dynamic color, decorative gradients, illustration,
  tertiary accent colors, scroll-jacked hero animations, serif display
  fonts, or marketing-site flourishes.
- Bypass token layers with one-off `Color(0xFF…)`, one-off
  `Modifier.background()`, one-off `tween(220)`, one-off `TextStyle(...)`.
- Write decorative copy. Strings are imperative and technical.

---

## What this system is **not** (guardrails)

- **Not** a Material You / dynamic-color system. Brand identity overrides
  system accents.
- **Not** a decorative system. Illustration, gradients, tertiary colors,
  and hero animation are intentionally absent.
- **Not** a marketing-site system. No parallax, no scroll-jacking, no serif
  display fonts.
- **Not** iOS. Touch targets, density, and motion are tuned for Android
  Material 3 Expressive, not for HIG.
- **Not** a multi-brand or white-label system. There is one brand.

New work that introduces any of the above should be flagged as a deliberate
departure with a written justification, not slipped in as a refinement.

---

## Substitutions & caveats

- **Fonts present** — Geist Sans (Regular/Medium/Bold), Geist Mono
  (Regular/Medium/Bold), Geist Pixel Circle. **No substitutions in place.**
- **Icons** — only the 6 hand-drawn brand-ship icons listed above are
  bundled. Anything else used in a mock should be sourced from Material
  Symbols at outline / weight 400 / 24×24, and the choice flagged.
- **Brand-mark variants** — the 6 launcher-foreground variants are
  technically "for the launcher icon" but I've kept them in `assets/icons/`
  for any future onboarding/splash exploration.
- The Compose-side spring specs (`damping 1.0 stiffness 500`) cannot be
  perfectly replicated in CSS; the `--rdp-ease-standard-spring` and
  `--rdp-ease-expressive-spring` tokens are approximations for HTML
  mockups only. **Production code must use `RipDpiMotion.*Spring()`**.
- **UI kit `ui_kits/android/`** — the JSX recreations approximate state
  transitions; they don't go through `RipDpi*StateTokens`. Treat as visual
  fidelity, not behavioural fidelity.
