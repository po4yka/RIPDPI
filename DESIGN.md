---
version: alpha
name: RIPDPI
description: Calm Material 3 Android VPN utility UI on a low-chroma neutral seed scheme, with compact operator tooling, explicit state visibility, and restrained semantic status color.
colors:
  primary: "#575F6B"
  background: "#FBF9FA"
  foreground: "#1B1B1D"
  card: "#FBF9FA"
  cardForeground: "#1B1B1D"
  muted: "#EAE7E9"
  mutedForeground: "#474748"
  accent: "#DFE2EB"
  accentForeground: "#42474E"
  border: "#C8C6C7"
  cardBorder: "#C8C6C7"
  inputBackground: "#E4E2E3"
  success: "#047857"
  warning: "#B45309"
  warningForeground: "#FFFFFF"
  warningContainer: "#FFF7ED"
  warningContainerForeground: "#1A1A1A"
  destructive: "#BA1A1A"
  destructiveForeground: "#FFFFFF"
  destructiveContainer: "#FFDAD6"
  destructiveContainerForeground: "#93000A"
  info: "#1D4ED8"
  infoForeground: "#FFFFFF"
  infoContainer: "#EFF6FF"
  infoContainerForeground: "#1E3A8A"
  restricted: "#6B7280"
  restrictedForeground: "#FFFFFF"
  restrictedContainer: "#F3F4F6"
  restrictedContainerForeground: "#374151"
  divider: "#C8C6C7"
  hairline: "#777778"
  outline: "#777778"
  outlineVariant: "#C8C6C7"
  scrim: "#000000"
typography:
  screenTitle:
    fontFamily: Geist Sans
    fontSize: 24px
    fontWeight: 500
    lineHeight: 32px
  appBarTitle:
    fontFamily: Geist Sans
    fontSize: 22px
    fontWeight: 500
    lineHeight: 28px
  cardTitle:
    fontFamily: Geist Sans
    fontSize: 18px
    fontWeight: 500
    lineHeight: 24px
  sheetTitle:
    fontFamily: Geist Sans
    fontSize: 22px
    fontWeight: 500
    lineHeight: 28px
  sectionTitle:
    fontFamily: Geist Sans
    fontSize: 14px
    fontWeight: 500
    lineHeight: 20px
    letterSpacing: 0.72px
  introAction:
    fontFamily: Geist Sans
    fontSize: 14px
    fontWeight: 400
    lineHeight: 21px
  introTitle:
    fontFamily: Geist Sans
    fontSize: 24px
    fontWeight: 500
    lineHeight: 32px
  introBody:
    fontFamily: Geist Sans
    fontSize: 16px
    fontWeight: 400
    lineHeight: 24px
  screenTitleEmphasis:
    fontFamily: Geist Sans
    fontSize: 24px
    fontWeight: 700
    lineHeight: 32px
  body:
    fontFamily: Geist Sans
    fontSize: 16px
    fontWeight: 400
    lineHeight: 24px
  bodyEmphasis:
    fontFamily: Geist Sans
    fontSize: 16px
    fontWeight: 500
    lineHeight: 24px
  bodyEmphasisBold:
    fontFamily: Geist Sans
    fontSize: 16px
    fontWeight: 700
    lineHeight: 24px
  secondaryBody:
    fontFamily: Geist Sans
    fontSize: 14px
    fontWeight: 400
    lineHeight: 20px
  caption:
    fontFamily: Geist Sans
    fontSize: 12px
    fontWeight: 400
    lineHeight: 16px
  smallLabel:
    fontFamily: Geist Sans
    fontSize: 12px
    fontWeight: 500
    lineHeight: 16px
  button:
    fontFamily: Geist Sans
    fontSize: 16px
    fontWeight: 500
    lineHeight: 24px
  navLabel:
    fontFamily: Geist Sans
    fontSize: 12px
    fontWeight: 500
    lineHeight: 16px
  monoValue:
    fontFamily: Geist Mono
    fontSize: 14px
    fontWeight: 400
    lineHeight: 20px
  monoConfig:
    fontFamily: Geist Mono
    fontSize: 14px
    fontWeight: 400
    lineHeight: 20px
  monoInline:
    fontFamily: Geist Mono
    fontSize: 13px
    fontWeight: 400
    lineHeight: 20px
  monoLog:
    fontFamily: Geist Mono
    fontSize: 12px
    fontWeight: 400
    lineHeight: 20px
  monoSmall:
    fontFamily: Geist Mono
    fontSize: 12px
    fontWeight: 400
    lineHeight: 16px
  brandMark:
    fontFamily: Geist Pixel Circle
    fontSize: 32px
    fontWeight: 400
    lineHeight: 48px
    letterSpacing: 0.8px
  brandStatus:
    fontFamily: Geist Mono
    fontSize: 13px
    fontWeight: 500
    lineHeight: 18px
rounded:
  xs: 4px
  sm: 8px
  md: 10px
  lg: 12px
  xl: 16px
  xxl: 28px
  xxxl: 48px
  full: 999px
spacing:
  xs: 4px
  sm: 8px
  md: 12px
  lg: 16px
  xl: 20px
  xxl: 24px
  xxxl: 32px
  section: 40px
  screen: 48px
components:
  screenCanvas:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    padding: "{spacing.screen}"
  modalScrim:
    backgroundColor: "{colors.scrim}"
    size: 48px
  screenTitlePanel:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    typography: "{typography.screenTitle}"
    padding: "{spacing.lg}"
  screenTitleEmphasisPanel:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    typography: "{typography.screenTitleEmphasis}"
    padding: "{spacing.lg}"
  sheetHeader:
    backgroundColor: "{colors.card}"
    textColor: "{colors.cardForeground}"
    typography: "{typography.sheetTitle}"
    rounded: "{rounded.xl}"
    padding: "{spacing.lg}"
  sectionLabel:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    typography: "{typography.sectionTitle}"
    padding: "{spacing.sm}"
  introHero:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    typography: "{typography.introTitle}"
    padding: "{spacing.xl}"
  introActionBar:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    typography: "{typography.introAction}"
    padding: "{spacing.md}"
  introBodyCopy:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    typography: "{typography.introBody}"
    padding: "{spacing.md}"
  textBody:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    typography: "{typography.body}"
    padding: "{spacing.sm}"
  textBodyEmphasis:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    typography: "{typography.bodyEmphasis}"
    padding: "{spacing.sm}"
  textBodyStrong:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    typography: "{typography.bodyEmphasisBold}"
    padding: "{spacing.sm}"
  textSecondary:
    backgroundColor: "{colors.background}"
    textColor: "{colors.mutedForeground}"
    typography: "{typography.secondaryBody}"
    padding: "{spacing.sm}"
  textCaption:
    backgroundColor: "{colors.background}"
    textColor: "{colors.mutedForeground}"
    typography: "{typography.caption}"
    padding: "{spacing.xs}"
  textSmallLabel:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    typography: "{typography.smallLabel}"
    padding: "{spacing.xs}"
  navItem:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    typography: "{typography.navLabel}"
    padding: "{spacing.sm}"
  inlineCode:
    backgroundColor: "{colors.muted}"
    textColor: "{colors.foreground}"
    typography: "{typography.monoInline}"
    rounded: "{rounded.sm}"
    padding: "{spacing.sm}"
  logRow:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    typography: "{typography.monoLog}"
    padding: "{spacing.sm}"
  denseTelemetry:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    typography: "{typography.monoSmall}"
    padding: "{spacing.xs}"
  brandMarkBanner:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    typography: "{typography.brandMark}"
    rounded: "{rounded.xxxl}"
    padding: "{spacing.xl}"
  statusLabel:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    typography: "{typography.brandStatus}"
    padding: "{spacing.xs}"
  buttonPrimary:
    backgroundColor: "{colors.primary}"
    textColor: "{colors.background}"
    typography: "{typography.button}"
    rounded: "{rounded.xl}"
    height: 48px
    padding: "{spacing.xl}"
  buttonSecondary:
    backgroundColor: "{colors.muted}"
    textColor: "{colors.foreground}"
    typography: "{typography.button}"
    rounded: "{rounded.xl}"
    height: 48px
    padding: "{spacing.xl}"
  buttonOutline:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    typography: "{typography.button}"
    rounded: "{rounded.xl}"
    height: 48px
    padding: "{spacing.xl}"
  buttonGhostDisabled:
    backgroundColor: "{colors.background}"
    textColor: "{colors.mutedForeground}"
    typography: "{typography.button}"
    rounded: "{rounded.xl}"
    height: 48px
    padding: "{spacing.xl}"
  buttonDestructive:
    backgroundColor: "{colors.destructive}"
    textColor: "{colors.destructiveForeground}"
    typography: "{typography.button}"
    rounded: "{rounded.xl}"
    height: 48px
    padding: "{spacing.xl}"
  iconButtonGhost:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    rounded: "{rounded.full}"
    size: 48px
  chipDefault:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    typography: "{typography.bodyEmphasis}"
    rounded: "{rounded.lg}"
    padding: "{spacing.md}"
  chipSelected:
    backgroundColor: "{colors.foreground}"
    textColor: "{colors.background}"
    typography: "{typography.bodyEmphasis}"
    rounded: "{rounded.lg}"
    padding: "{spacing.md}"
  textFieldDefault:
    backgroundColor: "{colors.inputBackground}"
    textColor: "{colors.foreground}"
    typography: "{typography.monoValue}"
    rounded: "{rounded.xl}"
    height: 48px
    padding: "{spacing.lg}"
  textFieldConfig:
    backgroundColor: "{colors.inputBackground}"
    textColor: "{colors.foreground}"
    typography: "{typography.monoConfig}"
    rounded: "{rounded.xl}"
    height: 96px
    padding: "{spacing.lg}"
  textFieldError:
    backgroundColor: "{colors.inputBackground}"
    textColor: "{colors.destructive}"
    typography: "{typography.monoValue}"
    rounded: "{rounded.xl}"
    height: 48px
    padding: "{spacing.lg}"
  cardOutlined:
    backgroundColor: "{colors.card}"
    textColor: "{colors.cardForeground}"
    rounded: "{rounded.xl}"
    padding: "{spacing.lg}"
  cardTonal:
    backgroundColor: "{colors.muted}"
    textColor: "{colors.foreground}"
    rounded: "{rounded.xl}"
    padding: "{spacing.lg}"
  cardElevated:
    backgroundColor: "{colors.card}"
    textColor: "{colors.cardForeground}"
    rounded: "{rounded.xl}"
    padding: "{spacing.lg}"
  cardStatus:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.accentForeground}"
    rounded: "{rounded.xl}"
    padding: "{spacing.lg}"
  routeProfileCard:
    backgroundColor: "{colors.card}"
    textColor: "{colors.cardForeground}"
    typography: "{typography.body}"
    rounded: "{rounded.xl}"
    padding: "{spacing.lg}"
  routeCapabilityPill:
    backgroundColor: "{colors.muted}"
    textColor: "{colors.foreground}"
    typography: "{typography.smallLabel}"
    rounded: "{rounded.full}"
    padding: "{spacing.sm}"
  routeStackDiagram:
    backgroundColor: "{colors.muted}"
    textColor: "{colors.foreground}"
    typography: "{typography.monoSmall}"
    rounded: "{rounded.lg}"
    padding: "{spacing.md}"
  routeOpportunityPanel:
    backgroundColor: "{colors.card}"
    textColor: "{colors.cardForeground}"
    typography: "{typography.body}"
    rounded: "{rounded.xl}"
    padding: "{spacing.lg}"
  settingsRow:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    typography: "{typography.body}"
    rounded: "{rounded.lg}"
    height: 52px
    padding: "{spacing.lg}"
  settingsRowSelected:
    backgroundColor: "{colors.accent}"
    textColor: "{colors.foreground}"
    typography: "{typography.bodyEmphasis}"
    rounded: "{rounded.lg}"
    height: 68px
    padding: "{spacing.lg}"
  topAppBar:
    backgroundColor: "{colors.background}"
    textColor: "{colors.foreground}"
    typography: "{typography.appBarTitle}"
    height: 56px
    padding: "{spacing.lg}"
  warningBadge:
    backgroundColor: "{colors.warning}"
    textColor: "{colors.warningForeground}"
    typography: "{typography.brandStatus}"
    rounded: "{rounded.full}"
    padding: "{spacing.sm}"
  successBadge:
    backgroundColor: "{colors.success}"
    textColor: "{colors.background}"
    typography: "{typography.brandStatus}"
    rounded: "{rounded.full}"
    padding: "{spacing.sm}"
  warningBanner:
    backgroundColor: "{colors.warningContainer}"
    textColor: "{colors.warningContainerForeground}"
    typography: "{typography.body}"
    rounded: "{rounded.xl}"
    padding: "{spacing.lg}"
  destructiveBanner:
    backgroundColor: "{colors.destructiveContainer}"
    textColor: "{colors.destructiveContainerForeground}"
    typography: "{typography.body}"
    rounded: "{rounded.xl}"
    padding: "{spacing.lg}"
  infoBadge:
    backgroundColor: "{colors.info}"
    textColor: "{colors.infoForeground}"
    typography: "{typography.brandStatus}"
    rounded: "{rounded.full}"
    padding: "{spacing.sm}"
  infoBanner:
    backgroundColor: "{colors.infoContainer}"
    textColor: "{colors.infoContainerForeground}"
    typography: "{typography.body}"
    rounded: "{rounded.xl}"
    padding: "{spacing.lg}"
  restrictedBadge:
    backgroundColor: "{colors.restricted}"
    textColor: "{colors.restrictedForeground}"
    typography: "{typography.brandStatus}"
    rounded: "{rounded.full}"
    padding: "{spacing.sm}"
  restrictedBanner:
    backgroundColor: "{colors.restrictedContainer}"
    textColor: "{colors.restrictedContainerForeground}"
    typography: "{typography.body}"
    rounded: "{rounded.xl}"
    padding: "{spacing.lg}"
  dividerRule:
    backgroundColor: "{colors.divider}"
    height: 1px
    width: 48px
  borderRule:
    backgroundColor: "{colors.border}"
    height: 1px
    width: 48px
  cardBorderRule:
    backgroundColor: "{colors.cardBorder}"
    height: 1px
    width: 48px
  outlineRule:
    backgroundColor: "{colors.outline}"
    height: 2px
    width: 48px
  outlineVariantRule:
    backgroundColor: "{colors.outlineVariant}"
    height: 1px
    width: 48px
  hairlineRule:
    backgroundColor: "{colors.hairline}"
    height: 1px
    width: 48px
---

# RIPDPI Design

## Overview

RIPDPI is an operator-facing Android utility, not a lifestyle product. The interface should feel quiet, deliberate, and technically trustworthy. The visual language is Material 3 on a low-chroma neutral seed: dense information, strong contrast, stable geometry, and narrow use of semantic status color only when the state itself matters.

The YAML tokens above describe the default light-theme baseline at standard contrast that agents can reuse across tools. The Compose implementation selects one of six generated Material 3 schemes (light and dark at standard, medium, and high contrast) and derives these roles from it; those rules are part of the design contract and are described in the prose below.

## Colors

Core UI uses the Material 3 color roles of a scheme generated with Google material-color-utilities (`SchemeNeutral`, seed `#36414F`, 2021 spec). `app/src/main/kotlin/com/poyka/ripdpi/ui/theme/RipDpiColorSchemes.kt` holds the generated values; regenerate them with the same tool instead of editing single colors. The RDS role names map onto Material roles:

- `background`, `card`, and `muted` define the page, card, and secondary-surface hierarchy.
- `foreground`, `cardForeground`, and `mutedForeground` define the text hierarchy.
- `primary` is the Material `primary` role, a calm slate. Filled primary actions use it with `onPrimary`.
- `background` is `surface`, `card` is `surface` (an outlined card bordered by `cardBorder`), `muted` is `surfaceContainerHigh`, `inputBackground` is `surfaceContainerHighest`, and `border`, `cardBorder`, and `divider` are `outlineVariant`.
- `accent` and `accentForeground` are for selected status cards or emphasized neutral surfaces, not for playful branding.
- `border`, `cardBorder`, `divider`, `hairline`, `outline`, and `outlineVariant` encode structure and focus.

Status color is intentionally scarce and semantic:

- `success` means connected, healthy, or passed.
- `warning` and `warningContainer` mean degraded or cautionary state.
- `destructive` and `destructiveContainer` mean dangerous, failed, reset, or disconnect.
- `info` and `infoContainer` mean explanatory state or guidance.
- `restricted` and `restrictedContainer` mean limited capability or unavailable mode.

Dark mode preserves the same role mapping instead of inventing a separate visual identity. Contrast levels select the matching generated scheme, which strengthens text, outline, and container relationships without making the app visually loud. `destructive` roles come from the Material `error` roles; `success`, `warning`, `info`, and `restricted` are fixed semantic roles outside the Material scheme.

Dynamic color is an opt-in Settings switch on Android 12 and later, off by default. When it is on, Material roles come from the system wallpaper scheme and the RDS roles derive from it; the fixed semantic status roles do not change.

## Typography

Typography uses bundled Geist families only:

- `Geist Sans` for general UI, titles, and labels.
- `Geist Mono` for telemetry, configuration, values, logs, and protocol-oriented strings.
- `Geist Pixel Circle` for the home brand mark only.

Primary content uses a readable mobile scale: `body` and its emphasis variants are `16sp/24sp`; supporting text uses `secondaryBody` at `14sp/20sp`. `cardTitle` is `18sp/24sp` for mode and profile names. Reserve `caption` at `12sp/16sp` for metadata. Use these semantic roles instead of shrinking text to fit a control. The portable YAML expresses text sizes in px; Android implements them in sp and respects system font scale.

Material `Typography` defines all fifteen baseline styles and all fifteen emphasized styles in Geist Sans. Display and headline styles use the Material 3 sizes; the other baseline styles map to the RDS roles above. Emphasized styles step up one weight (Normal to Medium, Medium to Bold), because the bundled Geist family ships Normal, Medium, and Bold.

Reserved usage matters:

- `brandMark` is home-header only.
- `brandStatus` is for compact status badges and telemetry labels.
- `sectionTitle` is for section grouping labels, not for body emphasis.
- `monoValue`, `monoConfig`, `monoInline`, `monoLog`, and `monoSmall` are operator-facing monospace roles.

## Layout

The spacing system is based on a practical 4px grid:

- `xs` to `lg` are for local control spacing and dense information layouts.
- `xl` to `screen` are for section rhythm, scaffold padding, and larger breaks.

Layout is adaptive and width-aware:

- `Compact`: under `600dp`
- `Medium`: `600dp` to `839dp`
- `Expanded`: `840dp` and above

Compact layouts should feel efficient, not cramped. Expanded layouts should split primary and secondary panels instead of stretching one long column edge to edge. Form and settings flows should stay narrower than dashboard-style content.

## Elevation & Depth

RIPDPI uses depth conservatively:

- Default cards stay flat and rely on borders and tonal separation first.
- Elevated cards and sheets are reserved for dialogs, bottom sheets, and higher-priority content.
- Status cards can carry modest lift, but decorative floating chrome is out of scope.
- Scrims should feel functional and calm, never cinematic.

## Surface Roles

Shared containers use named surface roles instead of one-off container and border recipes:

- Panel roles: standard card, tonal card, elevated card, status card, selected card.
- Modal roles: dialog and bottom sheet.
- Feedback roles: banner and snackbar.
- Chrome roles: dropdown menu, bottom bar, bottom-bar indicator, and switch thumb.
- Icon-badge roles: neutral dialog badge, destructive dialog badge, and bottom-sheet badge.

Role rules:

- `StatusCard` is neutral emphasis, not a warning or error container.
- `SelectedCard` is for explicit picked-state surfaces such as preset selection.
- Dialog and bottom-sheet surfaces may currently look aligned, but they are distinct roles and should stay named separately.
- Shared containers should resolve these roles from the theme surface-token layer instead of rebuilding container, border, content, or elevation inline.

## Shapes

Corners are rounded but restrained. Material `Shapes` covers the full Material 3 corner scale (4, 8, 12, 16, 20, 28, 32, and 48dp) and reuses the RDS radii:

- `xs`, `sm`, and `md` are for compact controls, small clusters, and subtle shape changes.
- `lg` and `xl` are the default radii for chips, rows, buttons, cards, and fields.
- `xxl`, `xxxl`, and `full` are for pills, hero surfaces, and circular controls where the shape communicates function.

The shape language should feel precise and engineered, not soft or playful.

## Components

The primary reusable components are:

- Actions: `RipDpiButton`, `RipDpiIconButton`, `RipDpiChip`
- Connection: `RipDpiConnectionActuator`
- Secure Routes: `RipDpiRouteProfileCard`, `RipDpiRouteCapabilityPill`, `RipDpiRouteStackDiagram`, `RipDpiRouteOpportunityPanel`
- Inputs: `RipDpiTextField`, `RipDpiConfigTextField`, `RipDpiDropdown`, `RipDpiSwitch`
- Containers: `RipDpiCard`, `PresetCard`, `SettingsRow`, `RipDpiDialog`, `RipDpiBottomSheet`, `RipDpiSnackbar`, `WarningBanner`
- Navigation: `RipDpiTopAppBar`, bottom navigation, `SettingsCategoryHeader`. Every screen uses the Material top app bar through `RipDpiTopAppBar`, except Home, which keeps its brand header (`HomeTopBar`) as the documented exception.
- Indicators: `StatusIndicator`, `LogRow`, `StageProgressIndicator`, `AnalysisProgressIndicator`, `RipDpiPageIndicators`
- Scaffolds: `RipDpiContentScreenScaffold`, `RipDpiSettingsScaffold`, `RipDpiDashboardScaffold`, `RipDpiIntroScaffold`

Component behavior rules:

- Primary buttons use `foreground` on `background` and keep a `48dp` minimum height.
- Secondary buttons use tonal neutral treatment instead of accent color.
- Ghost and outline actions stay visually light, but their disabled state must still be legible.
- Text fields and dropdowns use filled muted surfaces, not raw outlined-only treatment.
- Settings rows use `body` for the title and `secondaryBody` for the description. Text values appear below the description in the main text column and can wrap. Switches and chevrons use only their required width. Rows grow with content and clearly communicate selected, navigable, and toggle states.
- Diagnostics sections use a horizontal, scrollable Material 3 tab row at all font scales. Tabs retain selection semantics, localized labels, and section callbacks. Large font does not create a fixed vertical selector above the results.
- `RipDpiConnectionActuator` is the Home primary connection primitive: a horizontal slider-lock with fixed Network, DNS, Handshake, Tunnel, and Route stages, localized fault/warning treatment, a visible route label, and a locked terminal slot for connected or degraded-but-active sessions.
- Secure Route components describe any user-engageable protected path. Transport labels identify local VPN, local proxy, relay, WARP, or future provider VPN paths; capability pills identify VPN privacy, DNS protection, anti-DPI, relay, split/full tunnel, and traffic modification without making low-level techniques the product identity.
- Warning and remediation surfaces use semantic containers, not full-saturation fills by default.
- Monospace treatments belong in diagnostic or configuration contexts, not in general navigation chrome.
- Shared components should resolve pressed, focused, disabled, loading, selected, and error visuals from a first-class state-token layer instead of rebuilding those palettes inline.
- Shared panels, modals, menus, and navigation chrome should resolve their structure from first-class surface roles.

## Do's and Don'ts

Do:

- keep the default UI calm and low-chroma
- use semantic status color sparingly and intentionally
- reuse shared tokens before introducing local values
- preserve stable hierarchy in light, dark, and high-contrast modes
- favor dense, calm information presentation over decorative flourish
- keep state changes visible even when color is reduced

Don't:

- turn the app into a gradient-heavy consumer dashboard
- use bright accent color for routine actions
- hardcode one-off colors, spacing, shapes, or icon styles in screens
- use branding typography outside the reserved home-brand surface
- rely on color alone to communicate warning, selection, or disabled state
- expand forms or settings flows to full-width panels on large screens

## Accessibility

Accessibility is a design requirement, not an afterthought:

- Interactive elements must expose a semantic hit area of at least `48dp`.
- Buttons, icon buttons, inputs, switches, chips, and selectable cards must keep visible disabled, focused, pressed, loading, and error states where applicable.
- Icon-only buttons require a content description.
- `RipDpiDropdown`, `RipDpiTextField`, and `RipDpiSwitch` must surface labels and errors semantically.
- Secondary text roles must remain readable in both light and dark themes.
- Selectable surfaces such as `PresetCard` must expose selection state semantically, not only visually.
- Large font scale must not clip primary actions, titles, or onboarding content.
- A button height is a minimum. Let its complete label wrap and its container grow.
- Choice chips use content-driven width and height. Wrap the group instead of forcing equal columns.
- Place long localized facts and status badges on separate lines when they compete with a title. Diagnostic session, probe, and event rows reserve whole-word width before placing metadata beside their content. Report confidence, winner, and candidate headers use the same width rule. Full candidate titles and probe targets can wrap.
- Navigation follows the window width class: a bar on compact windows, a collapsed rail on medium and expanded windows, and an expanded rail (label beside icon) on large and extra-large windows. Medium and expanded windows also use the expanded rail at 1.2x font scale and above, so labels stay on one line. At 1.5x font scale and above, the bar shows one row of icons without visible labels; each icon keeps its label as its accessible name. Preserve destination order, selection, focus, and all actions.
- Use scrollable Material 3 tabs for peer sections. Do not shrink their text to fit.
- Keep graph labels readable. Scroll a large diagram instead of scaling its text below the chosen type size.
- Give large app-bar titles the full content width and as many lines as needed. Place navigation and actions in their own row only when controls exist.
- For sans text, use system hyphenation only when a word is wider than a complete line. Keep the chosen font size; technical monospace values keep their existing line rules.
- Text styles use the selected app language from resource configuration. This keeps script shaping and hyphenation consistent with visible localized strings.
- Theme options, DNS mode summaries, and status filters use localized presentation labels while retaining stable stored keys and protocol names. A language change refreshes projected permission guidance without a process restart.

## Motion

Motion should feel crisp and utilitarian:

- Quick interactions use `120ms` timing.
- General state changes use `220ms`.
- Emphasized entry and exit transitions use `320ms`.
- Route transitions use `260ms`.

Interaction scale is modest:

- Pressed surfaces can shrink to `0.98`.
- Selection can grow to `1.02`.
- Emphasis can reach `1.04`.

Compose implementation uses both cubic easing and spring motion. `MaterialTheme` receives `RipDpiMotionScheme`, derived from `RipDpiMotion`: Material fast, default, and slow specs map to the quick, state, and emphasized buckets, so Material and RDS components share one motion language. Reduced-motion behavior is mandatory:

- if animations are disabled, durations collapse to zero
- if reduced motion applies, durations are reduced and infinite motion is disabled
- press and emphasis springs must remain controlled and not become playful bounce animations
- shared controls and screens should consume semantic motion presets from `RipDpiMotion` instead of rebuilding raw tweens and visibility transitions inline
- quick, state, emphasized, and route buckets should remain distinct and should map to borders or alpha, content settling, section reveal, and navigation respectively
- infinite motion should be limited to status or progress signals that already respect reduced or static motion

## Iconography

Icon rules are strict:

- screens and components import icons only through `RipDpiIcons`
- standalone action and navigation icons use `24dp`; inline markers may use `16dp` or `18dp`
- SettingsRow navigation chevrons use `24dp`; leading settings, feedback, DNS, import, and recovery symbols use `32dp` inside a `40dp` circle for visual balance with the readable text scale
- compact action density changes padding; action and dismiss glyphs still use `24dp`
- chip glyphs use `18dp`; selection uses the existing state styling and keeps label size stable
- visible icon size is separate from the minimum `48dp` interactive area
- Launcher artwork previews use their matching asset backgrounds in both app themes and keep the full silhouette inside the mask. Foreground magnification follows each asset inset; do not apply one zoom to every artwork. Keep foreground assets and launcher aliases unchanged.
- the action and navigation icon language is outlined Material
- exceptions are limited to app assets and illustration surfaces
- iconography should read as utility chrome, not branding

## Theme Variants

RIPDPI has four runtime presentation dimensions:

- Light theme: the baseline defined in the YAML tokens above
- Dark theme: same hierarchy and semantics, inverted for low-light use without changing personality
- Contrast level: `Standard`, `Medium`, and `High`
- Dynamic color: off by default; an opt-in Settings switch on Android 12 and later

The contrast level follows the system setting on Android 14 and later (`UiModeManager.getContrast()`, updated while the app runs) and is `Standard` on older versions. Each level selects its generated Material 3 scheme, so Material-hosted and RDS components change together. Contrast changes tighten readability rather than recolor the app.

Agents should preserve semantic role names across variants rather than introducing theme-specific content branches unless a screen is intentionally showing theme comparison.

## Persona (Guided/Advanced)

RIPDPI uses one global persona, `Guided` or `Advanced`, set during onboarding and changeable from Settings.

- Guided keeps state visible but collapses low-level controls behind in-place Advanced expanders; it may choose defaults such as an automatic traffic-path strategy and one-tap apply only when verdict confidence is HIGH
- Guided must present a choice at MEDIUM or LOW verdict confidence instead of silently applying a recommendation
- Advanced exposes the full operator surface: probe matrix, replay, PCAP, archive export, profile binding, chain editing, TTL, engine parameters, CLI overrides, protocol grids, and manual strategy controls
- every Advanced-only control remains reachable from Guided through an in-place `Advanced` expander on the same screen or flow, not by requiring a separate hidden route
- persona changes must not fork the visual language; they adjust disclosure depth, autonomy, and defaults while preserving the same scaffolds, tokens, and status semantics

## Screen Recipes

Status:

- use `RipDpiDashboardScaffold`
- present a calm status header, three mode cards, and a single collapsible `Setup health` row instead of permanent warning banners
- keep the three `HomeModeCard` entries as the primary surface for Diagnose, Local bypass, and VPN; each card carries state, one primary action, configuration affordance, and inline disabled reason when gated
- show lockdown guidance whenever the configured path is the VPN, including idle, connecting, and a tunnel that just failed, because those are the states where the user can still act on it; show it as an item in the shared advisory card, never as a standalone permanent banner, and not at all for a proxy-only path
- reserve accent and status color for live connection, setup health, diagnostics verdicts, and recommendation state

Settings and advanced settings:

- use `RipDpiSettingsScaffold` or `RipDpiContentScreenScaffold` with form-width constraints
- compose with `SettingsRow`, `RipDpiCard`, and section labels
- keep controls dense, aligned, and monotone by default
- include the global Guided/Advanced persona toggle and navigation entries for advanced destinations such as Logs

Diagnose:

- structure Guided Diagnose as a diagnostics-first funnel: run a check, show a per-network verdict, then offer one-tap apply for the recommended traffic path when confidence allows it
- keep Advanced Diagnose available through in-place disclosure for the probe matrix, replay, PCAP, archive export, sessions, and events
- prefer card and banner groupings over unstructured long text
- use monospace styles for probes, labels, and low-level evidence where the data is operator-facing
- use semantic status color to distinguish health, caution, errors, and restricted actions

Logs and history:

- emphasize monospace rhythm and compact vertical spacing
- use `LogRow` and quiet separators
- keep chrome minimal so the data remains dominant

Connection and profiles:

- use filled text fields and monospace values for exact strings and protocol-oriented inputs
- fold preset and mode editing into one user-facing Profile concept; "Starter profile" names built-in recommended starting points, while saved user bundles are Profiles
- present Guided Connection as the configuration surface for the two traffic paths, with on/off, automatic strategy, Add server, and re-test entry points
- expose Advanced Connection controls for profile binding, protocol grids, chain relay, TTL, engine parameters, Finalmask, manual strategy, and CLI overrides through dense forms
- show validation and destructive affordances explicitly, never implicitly

Onboarding, auth, and permission flows:

- use `RipDpiIntroScaffold`
- center the core narrative, keep the footer action stable, and preserve large-font integrity
- use larger spacing and stronger title treatment without abandoning the calm low-chroma personality
- include persona selection before permissions, defer permissions until they are needed, and offer an optional first diagnostics check

## Layout Recipes

Two-column dashboard split:

- use `RipDpiDashboardScaffold` and prefer a primary and secondary column split on expanded widths
- keep the three mode cards and their primary actions in the primary reading order
- keep setup health, recent verdict memory, and supporting context secondary and collapsible unless active state requires attention
- collapse by stacking primary content before secondary content without changing section meaning

Dense settings list:

- use `RipDpiSettingsScaffold` for list-heavy settings and `RipDpiContentScreenScaffold` for bounded detail flows
- structure each category as a header plus one primary grouped card or container
- keep density in rows and fields, not in reduced hit targets or full-width sprawl
- let banners annotate an affected category only when state demands it; routine setup health belongs in rows, not persistent banners

Diagnostics evidence panel:

- use cards, headers, rows, and monospace values to group evidence into scannable blocks
- separate the Guided funnel, progress, verdict, recommendation, and latest evidence into clear section boundaries
- keep Advanced evidence controls in expanders, sheets, or dialogs when Guided persona is active
- move deep detail into sheets or dialogs instead of endlessly expanding the main stack
- use semantic tone only when it changes operator judgment

Monospace log stream:

- keep one dominant stream card under overview and filter controls
- render entries with `LogRow` and reserve `SettingsRow` for controls and facts
- keep timestamps, subsystem labels, and payload text in a compact monospace rhythm
- avoid per-entry cards, interleaved banners, or loud routine coloring

Intro hero plus footer flow:

- use `RipDpiIntroScaffold` with centered hero content and a stable bottom footer
- keep the top action row light and reserve primary decisions for the footer action cluster
- preserve bounded width and generous vertical gaps for large-font resilience
- do not move primary actions into the hero stack or let the footer drift with content height

Persona disclosure:

- use an in-place Advanced expander for every Guided screen that owns expert controls
- keep collapsed summaries factual enough that Guided users can see what state exists before expanding
- preserve route reachability for Advanced-only destinations through the owning Guided surface or Settings
- do not create parallel Guided and Advanced screen trees for the same task

## Screen Contracts

Status:

- build the dashboard from three `HomeModeCard` entries — Diagnose, Local bypass, and VPN — as the primary contract; each card owns its own title, summary, status, one primary action, configuration affordance, and inline "why disabled" text when gated
- keep the existing three-card `HomeModeCard` implementation contract intact while the tab is user-facing Status
- replace permanent warning, permission, and degradation banners with a single collapsible `Setup health` row that summarizes setup issues and expands to recovery actions
- show lockdown guidance while the configured path is the VPN, including idle, as an advisory-card item rather than a standalone permanent banner
- keep the connection actuator or equivalent enable control inside the relevant mode card; do not reintroduce a large circular VPN power button
- keep the default order: calm status header, collapsible `Setup health` row when applicable, the three mode cards, optional diagnostic PCAP toggle, then the Home diagnostic bottom-sheet host
- keep diagnostic detail in Home bottom sheets or the Diagnose screen rather than recreating an inline diagnostics card stack
- do not reintroduce the legacy `HomeStatusCard`, `HomeDiagnosticsCard`, `HomeStatsGrid`, `HomeApproachCard`, or `HomeHistoryCard` layout; their old test tags are reserved for negative regression assertions
- preserve the same mode-card order at expanded width rather than reviving the old two-column status/diagnostics split

Diagnose:

- anchor the screen with `RipDpiScreenScaffold`, `RipDpiTopAppBar`, and `DiagnosticsSectionSwitcher`
- make the Guided entry a diagnostics-first funnel: one check button, plain-language per-network verdict, and one-tap apply for the recommended traffic path
- auto-apply recommendations only at HIGH confidence; at MEDIUM or LOW confidence, present choices and explain uncertainty
- surface remembered per-network evidence, such as "worked last time on this network," without implying certainty beyond the stored verdict
- keep dashboard, scan, and tools responsibilities separated instead of mixing them into one card stack
- group evidence and recommendations with cards, banners, chips, rows, and dialogs rather than free-form text
- keep dashboard, scan, tools, sessions, and events as the Advanced information buckets
- keep probe matrix, replay, PCAP, archive export, sessions, and events reachable from Advanced disclosure
- reserve monospace treatment for operator-facing artifacts such as ids, domains, protocols, and candidate labels

Logs:

- give Logs a real navigation entry: it must be reachable from Diagnose Advanced tools and from Settings, not only through previews or internal routes
- keep the order `LogsToolbar` (filters and actions entry points), then `LogsStreamSection`; `LogsFiltersSection` opens in a bottom sheet from the toolbar, not inline
- use `SettingsRow` for filters and facts only, and `LogRow` for actual log entries
- keep the stream inside `LogsStreamCard` with `LogRow` and quiet separators
- render empty states as cards, not loose body text
- keep chrome minimal so the log data stays dominant

Settings:

- build the main settings surface with `RipDpiSettingsScaffold`
- compose sections from `SettingsCategoryHeader`, `RipDpiCard`, and `SettingsRow`
- group top-level settings as Connection, Interface, Privacy, and Support; keep permission repairs visible in Connection and maintenance actions under Support
- show actual language, theme, persona, and icon values; navigation-only rows keep a chevron without repeating Manage
- let banners precede the affected card, not replace the card structure
- use banners for cautions and permission guidance, and dialogs or form controls for edits and confirmations
- expose the global Guided/Advanced persona control and keep persona state persistent across process death
- include a Logs navigation row and any Advanced-only utility destinations that do not have a better owning surface
- keep advanced settings in the same dense list language instead of switching to a different visual system

Connection:

- treat Connection as the replacement for the former Config and Mode editor user-facing IA
- collapse preset and mode into one Profile concept; Starter profiles are built-in starting points, and saved user bundles are Profiles
- configure the two traffic paths here: Local bypass and VPN
- Guided Connection exposes on/off, "Path strategy: Auto", re-test strategies, Add server, paste/QR/scan import, and inferred protocol handling without a protocol grid
- Advanced Connection exposes the full protocol grid, Profile ID binding, chain relay, TTL, engine parameters, Finalmask, manual path strategy, CLI overrides, and exact text fields
- use Profile cards for profile choice and `SettingsRow` for summary values
- keep exact editing as a bounded form with network, relay, engine, and override sections, plus a persistent cancel/save bottom bar and explicit validation feedback
