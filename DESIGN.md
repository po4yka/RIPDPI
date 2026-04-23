---
version: alpha
name: RIPDPI
description: Monochrome-first Android VPN utility UI with compact operator tooling, explicit state visibility, and restrained semantic status color.
colors:
  primary: "#1A1A1A"
  background: "#FAFAFA"
  foreground: "#1A1A1A"
  card: "#FFFFFF"
  cardForeground: "#1A1A1A"
  muted: "#F5F5F5"
  mutedForeground: "#575757"
  accent: "#E8E8E8"
  accentForeground: "#1A1A1A"
  border: "#E0E0E0"
  cardBorder: "#E8E8E8"
  inputBackground: "#F5F5F5"
  success: "#047857"
  warning: "#B45309"
  warningForeground: "#FFFFFF"
  warningContainer: "#FFF7ED"
  warningContainerForeground: "#1A1A1A"
  destructive: "#B91C1C"
  destructiveForeground: "#FFFFFF"
  destructiveContainer: "#FEF2F2"
  destructiveContainerForeground: "#7F1D1D"
  info: "#1D4ED8"
  infoForeground: "#FFFFFF"
  infoContainer: "#EFF6FF"
  infoContainerForeground: "#1E3A8A"
  restricted: "#6B7280"
  restrictedForeground: "#FFFFFF"
  restrictedContainer: "#F3F4F6"
  restrictedContainerForeground: "#374151"
  divider: "#F0F0F0"
  hairline: "#666666"
  outline: "#757575"
  outlineVariant: "#D0D0D0"
  scrim: "#000000"
typography:
  screenTitle:
    fontFamily: Geist Sans
    fontSize: 22px
    fontWeight: 500
    lineHeight: 28px
  appBarTitle:
    fontFamily: Geist Sans
    fontSize: 20px
    fontWeight: 500
    lineHeight: 28px
  sheetTitle:
    fontFamily: Geist Sans
    fontSize: 18px
    fontWeight: 500
    lineHeight: 24px
  sectionTitle:
    fontFamily: Geist Sans
    fontSize: 13px
    fontWeight: 500
    lineHeight: 18px
    letterSpacing: 0.72px
  introAction:
    fontFamily: Geist Sans
    fontSize: 14px
    fontWeight: 400
    lineHeight: 21px
  introTitle:
    fontFamily: Geist Sans
    fontSize: 22px
    fontWeight: 500
    lineHeight: 30px
  introBody:
    fontFamily: Geist Sans
    fontSize: 15px
    fontWeight: 400
    lineHeight: 22px
  screenTitleEmphasis:
    fontFamily: Geist Sans
    fontSize: 22px
    fontWeight: 700
    lineHeight: 28px
  body:
    fontFamily: Geist Sans
    fontSize: 14px
    fontWeight: 400
    lineHeight: 20px
  bodyEmphasis:
    fontFamily: Geist Sans
    fontSize: 14px
    fontWeight: 500
    lineHeight: 20px
  bodyEmphasisBold:
    fontFamily: Geist Sans
    fontSize: 14px
    fontWeight: 700
    lineHeight: 20px
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
    fontSize: 15px
    fontWeight: 500
    lineHeight: 20px
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
  xlIncreased: 20px
  xxl: 28px
  xxlIncreased: 32px
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
    typography: "{typography.smallLabel}"
    rounded: "{rounded.lg}"
    padding: "{spacing.md}"
  chipSelected:
    backgroundColor: "{colors.foreground}"
    textColor: "{colors.background}"
    typography: "{typography.smallLabel}"
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

RIPDPI is an operator-facing Android utility, not a lifestyle product. The interface should feel quiet,
deliberate, and technically trustworthy. The visual language is monochrome-first: dense information, strong
contrast, stable geometry, and narrow use of semantic status color only when the state itself matters.

The YAML tokens above describe the default light-theme baseline that agents can reuse across tools. The
implementation adapts those roles for dark theme and high-contrast modes in Compose; those rules are part of
the design contract and are described in the prose below.

## Colors

Core UI uses achromatic structure:

- `background`, `card`, and `muted` define the page, card, and secondary-surface hierarchy.
- `foreground`, `cardForeground`, and `mutedForeground` define the text hierarchy.
- `primary` is a portability alias for the same deep ink role as `foreground`.
- `accent` and `accentForeground` are for selected status cards or emphasized neutral surfaces, not for
  playful branding.
- `border`, `cardBorder`, `divider`, `hairline`, `outline`, and `outlineVariant` encode structure and focus.

Status color is intentionally scarce and semantic:

- `success` means connected, healthy, or passed.
- `warning` and `warningContainer` mean degraded or cautionary state.
- `destructive` and `destructiveContainer` mean dangerous, failed, reset, or disconnect.
- `info` and `infoContainer` mean explanatory state or guidance.
- `restricted` and `restrictedContainer` mean limited capability or unavailable mode.

Dark mode preserves the same role mapping instead of inventing a separate visual identity. Contrast settings
increase readability by tightening muted, border, and outline relationships without making the app visually loud.

## Typography

Typography uses bundled Geist families only:

- `Geist Sans` for general UI, titles, and labels.
- `Geist Mono` for telemetry, configuration, values, logs, and protocol-oriented strings.
- `Geist Pixel Circle` for the home brand mark only.

Text should stay compact and readable. Emphasis comes from weight promotion before size changes. Agents should
prefer `bodyEmphasis` and `bodyEmphasisBold` over inventing larger ad hoc styles for routine emphasis.

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

Compact layouts should feel efficient, not cramped. Expanded layouts should split primary and secondary
panels instead of stretching one long column edge to edge. Form and settings flows should stay narrower than
dashboard-style content.

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
- Shared containers should resolve these roles from the theme surface-token layer instead of rebuilding container,
  border, content, or elevation inline.

## Shapes

Corners are rounded but restrained:

- `xs`, `sm`, and `md` are for compact controls, small clusters, and subtle shape changes.
- `lg` and `xl` are the default radii for chips, rows, buttons, cards, and fields.
- `xlIncreased` and `xxlIncreased` are for expressive pressed or emphasized states, not a new default baseline.
- `xxl`, `xxxl`, and `full` are for pills, hero surfaces, and circular controls where the shape communicates function.

The shape language should feel precise and engineered, not soft or playful.

## Components

The primary reusable components are:

- Actions: `RipDpiButton`, `RipDpiIconButton`, `RipDpiChip`
- Inputs: `RipDpiTextField`, `RipDpiConfigTextField`, `RipDpiDropdown`, `RipDpiSwitch`
- Containers: `RipDpiCard`, `PresetCard`, `SettingsRow`, `RipDpiDialog`, `RipDpiBottomSheet`, `RipDpiSnackbar`, `WarningBanner`
- Navigation: `RipDpiTopAppBar`, bottom navigation, `SettingsCategoryHeader`
- Indicators: `StatusIndicator`, `LogRow`, `StageProgressIndicator`, `AnalysisProgressIndicator`, `RipDpiPageIndicators`
- Scaffolds: `RipDpiContentScreenScaffold`, `RipDpiSettingsScaffold`, `RipDpiDashboardScaffold`, `RipDpiIntroScaffold`

Component behavior rules:

- Primary buttons use `foreground` on `background` and keep a `48dp` minimum height.
- Secondary buttons use tonal neutral treatment instead of accent color.
- Ghost and outline actions stay visually light, but their disabled state must still be legible.
- Text fields and dropdowns use filled muted surfaces, not raw outlined-only treatment.
- Settings rows use stable vertical rhythm and must clearly communicate selected, navigable, and toggle states.
- Warning and remediation surfaces use semantic containers, not full-saturation fills by default.
- Monospace treatments belong in diagnostic or configuration contexts, not in general navigation chrome.
- Shared components should resolve pressed, focused, disabled, loading, selected, and error visuals from a first-class state-token layer instead of rebuilding those palettes inline.
- Shared panels, modals, menus, and navigation chrome should resolve their structure from first-class surface roles.

## Do's and Don'ts

Do:

- keep the default UI monochrome-first
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
- Buttons, icon buttons, inputs, switches, chips, and selectable cards must keep visible disabled, focused,
  pressed, loading, and error states where applicable.
- Icon-only buttons require a content description.
- `RipDpiDropdown`, `RipDpiTextField`, and `RipDpiSwitch` must surface labels and errors semantically.
- Secondary text roles must remain readable in both light and dark themes.
- Selectable surfaces such as `PresetCard` must expose selection state semantically, not only visually.
- Large font scale must not clip primary actions, titles, or onboarding content.

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

Compose implementation uses both cubic easing and spring motion. Reduced-motion behavior is mandatory:

- if animations are disabled, durations collapse to zero
- if reduced motion applies, durations are reduced and infinite motion is disabled
- press and emphasis springs must remain controlled and not become playful bounce animations
- shared controls and screens should consume semantic motion presets from `RipDpiMotion` instead of rebuilding raw tweens and visibility transitions inline
- quick, state, emphasized, and route buckets should remain distinct and should map to borders or alpha, content settling, section reveal, and navigation respectively
- infinite motion should be limited to status or progress signals that already respect reduced or static motion

## Iconography

Icon rules are strict:

- screens and components import icons only through `RipDpiIcons`
- the action and navigation icon language is outlined Material
- exceptions are limited to app assets and illustration surfaces
- iconography should read as utility chrome, not branding

## Theme Variants

RIPDPI has three runtime presentation dimensions:

- Light theme: the baseline defined in the YAML tokens above
- Dark theme: same hierarchy and semantics, inverted for low-light use without changing personality
- Contrast level: `Standard`, `Medium`, and `High`

Contrast changes tighten readability rather than recolor the app:

- `Medium` strengthens muted text and borders
- `High` strengthens muted text, borders, and outline relationships further
- dark high-contrast also nudges `inputBackground` toward stronger separation

Agents should preserve semantic role names across variants rather than introducing theme-specific content
branches unless a screen is intentionally showing theme comparison.

## Screen Recipes

Home:

- use `RipDpiDashboardScaffold`
- present a calm status header, primary connection actions, and split primary and secondary panels on expanded widths
- reserve accent and status color for live connection, health, and recommendation state

Settings and advanced settings:

- use `RipDpiSettingsScaffold` or `RipDpiContentScreenScaffold` with form-width constraints
- compose with `SettingsRow`, `RipDpiCard`, and section labels
- keep controls dense, aligned, and monotone by default

Diagnostics:

- prefer card and banner groupings over unstructured long text
- use monospace styles for probes, labels, and low-level evidence where the data is operator-facing
- use semantic status color to distinguish health, caution, errors, and restricted actions

Logs and history:

- emphasize monospace rhythm and compact vertical spacing
- use `LogRow` and quiet separators
- keep chrome minimal so the data remains dominant

Config and mode editor:

- use filled text fields and monospace values for exact strings and protocol-oriented inputs
- show validation and destructive affordances explicitly, never implicitly

Onboarding, auth, and permission flows:

- use `RipDpiIntroScaffold`
- center the core narrative, keep the footer action stable, and preserve large-font integrity
- use larger spacing and stronger title treatment without abandoning the monochrome personality

## Layout Recipes

Two-column dashboard split:

- use `RipDpiDashboardScaffold` and prefer a primary and secondary column split on expanded widths
- keep status, primary actions, and live remediation in the primary column
- keep overview, history, and supporting context in the secondary column
- collapse by stacking primary content before secondary content without changing section meaning

Dense settings list:

- use `RipDpiSettingsScaffold` for list-heavy settings and `RipDpiContentScreenScaffold` for bounded detail flows
- structure each category as a header plus one primary grouped card or container
- keep density in rows and fields, not in reduced hit targets or full-width sprawl
- let banners annotate a category from above instead of replacing the category structure

Diagnostics evidence panel:

- use cards, headers, rows, and monospace values to group evidence into scannable blocks
- separate workflow controls, progress, recommendations, and latest evidence into clear section boundaries
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

## Screen Contracts

Home:

- keep banners above the dashboard content
- make `HomeStatusCard` the first primary block and `HomeDiagnosticsCard` the second
- keep the default order: banners, status, diagnostics, optional approach, history, then stats
- keep approach, history, and stats as supporting content rather than competing hero panels
- preserve the expanded-width split between primary operational panels and secondary overview content

Diagnostics:

- anchor the screen with `RipDpiScreenScaffold`, `RipDpiTopAppBar`, and `DiagnosticsSectionSwitcher`
- keep dashboard, scan, and tools responsibilities separated instead of mixing them into one card stack
- group evidence and recommendations with cards, banners, chips, rows, and dialogs rather than free-form text
- keep dashboard, scan, tools, sessions, and events as the top-level information buckets
- reserve monospace treatment for operator-facing artifacts such as ids, domains, protocols, and candidate labels

Logs:

- keep the order `LogsOverviewCard`, `LogsFiltersSection`, then the stream section
- use `SettingsRow` for filters and facts only, and `LogRow` for actual log entries
- keep the stream inside `LogsStreamCard` with `LogRow` and quiet separators
- render empty states as cards, not loose body text
- keep chrome minimal so the log data stays dominant

Settings:

- build the main settings surface with `RipDpiSettingsScaffold`
- compose sections from `SettingsCategoryHeader`, `RipDpiCard`, and `SettingsRow`
- let banners precede the affected card, not replace the card structure
- use banners for cautions and permission guidance, and dialogs or form controls for edits and confirmations
- keep advanced settings in the same dense list language instead of switching to a different visual system

Config:

- keep `ConfigScreen` for preset browsing and `ModeEditorScreen` for exact editing
- preserve the overview, presets, and summary structure on `ConfigScreen`
- use `PresetCard` for preset choice and `SettingsRow` for summary values
- keep `ModeEditorScreen` as a bounded form with network, relay, engine, and override sections, plus a persistent cancel/save bottom bar and explicit validation feedback
