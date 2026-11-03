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
  tonalCardBorder: "#00000000"
  primaryAction: "#1B1B1D"
  primaryActionForeground: "#FBF9FA"
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
    backgroundColor: "{colors.primaryAction}"
    textColor: "{colors.primaryActionForeground}"
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
    textColor: "{colors.foreground}"
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
    backgroundColor: "#F0EDEE"
    textColor: "{colors.foreground}"
    rounded: "{rounded.xl}"
    padding: "{spacing.lg}"
  cardElevated:
    backgroundColor: "{colors.card}"
    textColor: "{colors.cardForeground}"
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

### Product principle

The standard is Linear's website or Apple Notes: a simple purpose, a simple architecture, a simple design, and a simple implementation. This principle comes before every other rule in this file.

Three people open every screen: the user (UX), the developer who reads its code (DX), and the agent that edits it next (AX). Serve all three. A screen that looks clean but hides its logic in a one-off layout robs the developer and the agent; a tidy component tree that confuses the user robs the user.

The layout carries the meaning, not the words around it:

- Cut taglines, helper text, and secondary labels.
- Remove any label that only exists to explain the layout. If a screen needs a caption to make sense, the screen is broken: fix its hierarchy, grouping, order, or control choice, then delete the caption.
- Do not add a subtitle that repeats its title, a description that repeats its control label, or a section header above a single item.

Text that stays because no layout can carry it:

- the content itself: titles, control labels, values, and data
- live state: status, progress, and the reason a control is disabled
- errors and validation feedback
- the consequence of a destructive or irreversible action
- required disclosures: VPN, privacy, permission, and legal text
- accessibility names: content descriptions and semantic labels; removing visible text never removes the accessible name

For DX and AX, a simple screen uses the shared scaffolds and components in [Components](#components), keeps one owner for each piece of state, and reads top to bottom in the same order the user sees it.

## Colors

Core UI uses the Material 3 color roles of a scheme generated with Google material-color-utilities (`SchemeNeutral`, seed `#36414F`, 2021 spec). `app/src/main/kotlin/com/poyka/ripdpi/ui/theme/RipDpiColorSchemes.kt` holds the generated values; regenerate them with the same tool instead of editing single colors. The RDS role names map onto Material roles:

- `background`, `card`, and `muted` define the page, card, and secondary-surface hierarchy.
- `foreground`, `cardForeground`, and `mutedForeground` define the text hierarchy.
- `primary` is the Material `primary` role, a calm slate. Filled Primary buttons do not use it: the Primary button is neutral ink, the `primaryAction` container with the `primaryActionForeground` label, so it is the strongest control on the page. In dark theme the container is full white so it stays clearly stronger than tonal fills and the selected chip. A disabled or loading Primary uses `border` with `mutedForeground`, which stays visibly different in both themes.
- `background` is `surface`, `card` is `surface` (an outlined card bordered by `cardBorder`), `muted` is `surfaceContainerHigh`, `inputBackground` is `surfaceContainerHighest`, and `border`, `cardBorder`, and `divider` are `outlineVariant`.
- `accent` and `accentForeground` are for selected status cards or emphasized neutral surfaces, not for playful branding. `accent` is `secondaryContainer` and `accentForeground` is `onSecondaryContainer`: content on an accent container always uses `accentForeground`, never `foreground`, which falls to 1.8:1 at high contrast.
- `border`, `cardBorder`, `divider`, `hairline`, `outline`, and `outlineVariant` encode structure and focus.

Status color is intentionally scarce and semantic:

- `success` means connected, healthy, or passed.
- `warning` and `warningContainer` mean degraded or cautionary state.
- `destructive` and `destructiveContainer` mean dangerous, failed, reset, or disconnect.
- `info` and `infoContainer` mean explanatory state or guidance.
- `restricted` and `restrictedContainer` mean limited capability or unavailable mode.

Dark mode preserves the same role mapping instead of inventing a separate visual identity. Contrast levels select the matching generated scheme, which strengthens text, outline, and container relationships without making the app visually loud. `destructive` roles come from the Material `error` roles; `success`, `warning`, `info`, and `restricted` are fixed semantic hues outside the Material scheme, but their tone colors follow the contrast level: Medium and High darken them in light and lighten them in dark, so a tone holds at least 4.5:1 against the page at every level and at least 7:1 at High. `RipDpiRoleContrastTest` checks every surface, banner, actuator, and route role in light, dark, medium, and high.

Dynamic color is an opt-in Settings switch on Android 12 and later, off by default. When it is on, Material roles come from the system wallpaper scheme and the RDS roles derive from it; the fixed semantic status roles do not change.

## Typography

Typography uses bundled Geist families only:

- `Geist Sans` for general UI, titles, and labels.
- `Geist Mono` for telemetry, configuration, values, logs, and protocol-oriented strings.
- `Geist Pixel Circle` for the home brand mark only.

Primary content uses a readable mobile scale: `body` and its emphasis variants are `16sp/24sp`; supporting text uses `secondaryBody` at `14sp/20sp`. `cardTitle` is `18sp/24sp` for mode and profile names. Reserve `caption` at `12sp/16sp` for metadata, never for text that explains the layout. Use these semantic roles instead of shrinking text to fit a control. The portable YAML expresses text sizes in px; Android implements them in sp and respects system font scale.

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

Compact layouts should feel efficient, not cramped. Expanded layouts should split primary and secondary panels instead of stretching one long column edge to edge. Settings, History, and Logs use list-detail from the `Expanded` class, and Diagnostics hosts its details in a detail pane. Home and the mode editor use a supporting pane from the same class. Content width is capped per size class. Form and settings flows should stay narrower than dashboard-style content.

## Elevation & Depth

RIPDPI uses depth conservatively:

- One card rule: `surfaceContainer` tonal steps group content, an outline marks only interactive or selected cards, and a shadow marks only hero or selected cards.
- Grouping uses the tonal card (`surfaceContainer`, no shadow, and no outline at standard contrast; at medium and high contrast it gets an `outlineVariant` edge through `tonalCardBorder`, because the fill alone is about 1.05:1 against the page). A tonal card nested in a tonal card steps one container up (`surfaceContainerHigh`).
- Interactive cards use the outlined card. Selected or focused cards use the selected card.
- The elevated card is the hero role, for the top summary of a screen. Ordinary content never uses it.
- Decorative floating chrome is out of scope.
- Card roles draw their shadow directly, not through Material card elevation, so the shadow stays the same while a card is pressed or focused and a surface-colored card gets no tonal tint.
- Scrims should feel functional and calm, never cinematic.

## Surface Roles

Shared containers use named surface roles instead of one-off container and border recipes:

- Panel roles: standard card, tonal card, elevated card, selected card.
- Modal roles: dialog and bottom sheet.
- Feedback roles: banner and snackbar.
- Chrome roles: dropdown menu, bottom bar, bottom-bar indicator, and switch thumb.
- Icon-badge roles: neutral dialog badge, destructive dialog badge, and bottom-sheet badge.

Role rules:

- `TonalCard` groups ordinary content. `Card` (outlined) is for interactive cards. `ElevatedCard` is for hero use only.
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
- Text fields and dropdowns use filled muted surfaces, not raw outlined-only treatment. Their idle boundary and the outline button boundary use the `outline` role, which holds 3:1; field text uses `foreground` in every state, including error, which shows in the border, trailing icon and supporting text only, and a disabled outline button uses `onSurface` at 12%. `Hidden` and `RevealLastTyped` render a secure field that offers no copy or cut and no keyboard learning, and a read-only secure field rejects edits. `Visible` renders a plain, readable, copyable field, so a revealed secret stays readable to a screen reader; Compose has no incognito keyboard flag, so a field that must stay out of suggestions asks for a URI keyboard without autocorrect.
- Settings rows use `body` for the title. A row has a `secondaryBody` description only when it shows a value or state; a description that repeats the title or explains the control is cut. Text values appear in the main text column and can wrap. Switches and chevrons use only their required width. Rows grow with content and clearly communicate selected, navigable, and toggle states.
- Diagnostics sections use a horizontal, scrollable Material 3 tab row at all font scales. Tabs retain selection semantics, localized labels, and section callbacks. Large font does not create a fixed vertical selector above the results.
- `RipDpiConnectionActuator` is the Home primary connection primitive: a horizontal slider-lock with fixed Network, DNS, Handshake, Tunnel, and Route stages, localized fault/warning treatment, a visible route label, and a locked terminal slot for connected or degraded-but-active sessions.
- Secure Route components describe any user-engageable protected path. Transport labels identify local VPN, local proxy, relay, WARP, or future provider VPN paths; capability pills identify VPN privacy, DNS protection, anti-DPI, relay, split/full tunnel, and traffic modification without making low-level techniques the product identity.
- Warning and remediation surfaces use semantic containers, not full-saturation fills by default.
- Monospace treatments belong in diagnostic or configuration contexts, not in general navigation chrome.
- Facade components built on Material 3 take pressed, focused, disabled, and selected visuals from Material and map RDS roles into its color objects (for example `ripDpiButtonColors`). States Material does not model (banner tone, actuator, route availability) resolve from the first-class state-token layer instead of rebuilding those palettes inline.
- Shared panels, modals, menus, and navigation chrome should resolve their structure from first-class surface roles.

## Do's and Don'ts

Do:

- keep the default UI calm and low-chroma
- use semantic status color sparingly and intentionally
- reuse shared tokens before introducing local values
- preserve stable hierarchy in light, dark, and high-contrast modes
- favor dense, calm information presentation over decorative flourish
- keep state changes visible even when color is reduced
- fix the layout when a screen seems to need explanation

Don't:

- add taglines, helper text, secondary labels, or captions that explain the layout

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
- Buttons, icon buttons and text actions draw a 2dp keyboard focus ring in ink or paper, whichever holds 3:1 against the container (`ripDpiFocusRing`); a text action draws it 4dp outside its bounds so it clears the label. Ripple state layers alone do not meet 3:1.
- Icon-only buttons require a content description.
- `RipDpiDropdown`, `RipDpiTextField`, and `RipDpiSwitch` must surface labels and errors semantically. The dropdown is one merged node: the label as its description, the selected value as its state, the error, the dropdown role, and expand or collapse actions.
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

Each screen has one scaffold, one layout recipe, and one contract. Look up the screen here, then read its recipe and contract below.

| Screen | Scaffold | Layout recipe | Contract |
| --- | --- | --- | --- |
| Status (Home) | `RipDpiDashboardScaffold` | Two-column dashboard split | [Status](#status) |
| Diagnostics | `RipDpiScreenScaffold` | Diagnostics evidence panel | [Diagnostics](#diagnostics) |
| Logs and History | `RipDpiScreenScaffold` | Monospace log stream | [Logs](#logs) |
| Settings | `RipDpiSettingsScaffold` | Dense settings list | [Settings](#settings) |
| Connection and editors | `RipDpiScreenScaffold` | Dense settings list | [Connection](#connection) |
| Onboarding, auth, and permissions | `RipDpiIntroScaffold` | Intro hero and footer flow | [Onboarding](#onboarding) |

## Layout Recipes

### Two-column dashboard split

- From the Expanded width class, split a primary and a supporting column. Below it, stack primary before supporting content in the same order and with the same meaning.
- The primary column holds current state, primary actions, and live remediation. The supporting column holds overview, history, and lower-priority context; it stays collapsible unless active state needs attention.
- Separate concerns with section gaps and card boundaries, not nested background panels. Do not stretch cards edge to edge or add a third action rail.

### Dense settings list

- Use `RipDpiSettingsScaffold` for list-heavy settings and `RipDpiContentScreenScaffold` for bounded detail flows, both at form width.
- Each category is a `SettingsCategoryHeader` plus one primary `RipDpiCard` that holds `SettingsRow`, fields, or toggles.
- Density comes from compact rows and a stable divider rhythm, not from smaller hit targets.
- A banner sits immediately above the affected category card, only when state needs it. Routine setup health belongs in rows.
- Do not mix free-floating controls, bespoke cards, and row groups in one category.

### Diagnostics evidence panel

- Frame evidence in `RipDpiCard` and group it with `SettingsCategoryHeader`, `SettingsRow`, `StatusIndicator`, and monospace value roles.
- Give workflow controls, progress, verdict, recommendation, and latest evidence separate card or section boundaries.
- Put labels at the start and stable values at the end or below. Use semantic tone only where it changes the operator's decision.
- Open session, event, and probe detail in `RipDpiBottomSheet` or a dialog instead of expanding the main stack.
- Do not use ungrouped long text or feature-local tinted containers.

### Monospace log stream

- Put one dominant `LogsStreamCard` under a compact control row. Open filters and secondary actions in sheets.
- Render every entry with `LogRow`. Use `SettingsRow` only for controls, binary filters, and facts.
- Keep timestamps, subsystem labels, and payload text in a compact monospace rhythm with quiet separators.
- Show empty and filtered-empty states as a card in the stream slot, so the layout does not jump.
- Do not wrap entries in cards, put banners between entries, or color routine rows.

### Intro hero and footer flow

- Use `RipDpiIntroScaffold` with `rememberRipDpiIntroScaffoldMetrics()`: a light top action row, a centered hero, and a pinned footer.
- The hero holds an illustration or badge, a title, a body, and optional inline content.
- The top row holds only skip, dismiss, or light navigation. Primary decisions belong in the footer action cluster.
- Keep bounded width and generous vertical gaps for large fonts. Do not shrink controls, and do not let the footer move with content height.

### Persona disclosure

- Every Guided screen that owns expert controls uses an in-place Advanced expander.
- A collapsed summary states which state exists, so a Guided user sees it before expanding.
- Advanced-only destinations stay reachable through the owning Guided surface or Settings.
- Do not create parallel Guided and Advanced screen trees for the same task.

## Screen Contracts

### Status

- The primary pane holds, in order: `RipDpiConnectionActuator` with its pause controls, the advisory slot, connection measurements, and the applied configuration panel.
- The supporting pane (below the primary pane on compact widths) holds the connection-health entry, then the `Modes & diagnostics` disclosure, collapsed by default. Its stable automation ID belongs to the clickable header; the collapsed and expanded IDs are state markers only.
- The disclosure holds three `HomeModeCard` entries in this order: Proxy only, Whole device, and Diagnose. Proxy only and Whole device name the scope; each shows its path (Direct, or Relay and the server) and status, and has a configure affordance but no primary action. Only Diagnose has a primary action: it runs the Home analysis in place, and its body and configure affordance open Diagnostics. `RipDpiConnectionActuator` is the only connect and disconnect control in the app.
- After the cards come the optional diagnostic PCAP toggle and the Home diagnostic bottom-sheet host. Diagnostic detail lives in those sheets or in Diagnostics.
- A connection fault message stays with the actuator. Do not repeat it in a banner.
- Setup health, permission recovery, and lockdown guidance are items in the shared advisory slot, never permanent standalone banners. Show lockdown guidance whenever the configured path is the VPN, including idle, connecting, and a tunnel that just failed; do not show it for a proxy-only path.
- Reserve accent and status color for the live connection, setup health, diagnostics verdicts, and recommendation state.
- Do not reintroduce `HomeStatusCard`, `HomeDiagnosticsCard`, `HomeStatsGrid`, `HomeApproachCard`, or `HomeHistoryCard`; their old test tags are reserved for negative regression assertions. Do not add a large circular power button, decorative hero art, floating metric tiles, inline raw diagnostics tables, or the old two-column status and diagnostics split.

### Diagnostics

- Anchor the screen with `RipDpiTopAppBar` and `DiagnosticsSectionSwitcher`, followed by section content in the pager. From the Expanded width class, details open in a detail pane; below it, in a sheet.
- The Guided entry is a diagnostics-first funnel: one check button, a plain-language verdict for the network, and one-tap apply of the recommended traffic path.
- Apply a recommendation automatically only at HIGH confidence. At MEDIUM or LOW confidence, present the choices and state the uncertainty.
- Show remembered evidence for the network, such as "worked last time on this network", without claiming more certainty than the stored verdict.
- Dashboard, Scan, and Tools are the only sections (`DiagnosticsSection`). Attach new content to one of them before adding a section. Past sessions and events live in History (Connections, Diagnostics, and Events), which the Diagnostics top app bar opens. Advanced disclosure keeps the probe matrix, replay, PCAP, and archive export reachable.
- Dashboard owns the health summary, active profile, remembered-network state, and grouped warnings. Scan owns profile selection, workflow controls, live progress, the latest session, strategy reports, and the latest evidence; its actions stay in the scan card, never in a floating action button. Tools owns approach selection, preview and export actions, and optional debug or performance cards.
- Show one primary outcome with metadata from the same evidence source. Put historical and technical evidence below it, and name missing metadata explicitly.
- Group evidence with `RipDpiCard`, `WarningBanner`, `SettingsRow`, `StatusIndicator`, `RipDpiChip`, and `RipDpiBottomSheet`. Use monospace roles for IDs, domains, resolver names, protocols, and candidate labels.
- Destructive or high-risk actions sit behind explicit buttons or `RipDpiDialog`, never behind a row tap. Feedback goes through `RipDpiSnackbarHost` or semantic banners.

### Logs

- Logs is reachable from Diagnostics Advanced tools and from Settings, not only through previews or internal routes.
- Keep this order: `LogsToolbar` (status and the Filters entry), a search `RipDpiTextField` (not a Material `SearchBar`), then `LogsStreamSection`, which fills the remaining height as the screen's only vertical list.
- `LogsFiltersSection` opens in a scrollable bottom sheet with a Done action. Filters use `SettingsRow`, `RipDpiChip`, and `SettingsCategoryHeader`.
- Save is a top app bar action. Support-bundle sharing and Clear are items of `RipDpiOverflowMenu`. Clear asks for confirmation in a `RipDpiDialog` before it removes the logs.
- From the Expanded width class, Logs shows the list and a detail pane for the selected entry.
- Empty states use `RipDpiEmptyState` through `LogsEmptyState`, never a raw placeholder string.
- Copy, save, clear, and support-bundle feedback goes to a snackbar. The stream needs no simulated refresh delay.

### Settings

- Use `RipDpiContentScreenScaffold` only for narrower detail flows that do not behave like the main list.
- Keep four top-level groups: Connection (permission repairs, start on boot), Interface (language, theme, persona, icon), Privacy (data transparency), and Support (backup, maintenance, and the Logs row).
- Show actual values, such as the native language name, theme, persona, and icon. A navigation-only row keeps its chevron, without a repeated Manage value or an invented count.
- Use `WarningBanner` for tunneled-outbound cautions, permission guidance, and stateful advisories. Never encode state only through row description color.
- Edit through `RipDpiDropdown`, `RipDpiTextField`, `RipDpiConfigTextField`, and dialogs, not inline in row descriptions.
- The global Guided/Advanced persona control persists across process death. Advanced settings keep the same primitives and list language.

### Connection

- Connection replaces the former Config and Mode editor screens. Starter profiles are built-in starting points; saved user bundles are Profiles.
- Keep this order: the saved connection card with its Change profile control, runtime feedback (permission recovery and errors, shown only when present), the profile choices (starter profiles and relay profiles, shown only after Change profile), one card with the traffic-mode chips and the section switcher, the selected section, then connection guidance.
- The scope chips read Whole device and Proxy only; the sections read Direct and Relay. Connection has no connect or disconnect control: connecting belongs to the Status actuator.
- Guided Connection exposes no on/off, "Path strategy: Auto", re-test strategies, Add server, and paste, QR, and scan import, without a protocol grid. Advanced Connection exposes the protocol grid, Profile ID binding, chain relay, TTL, engine parameters, Finalmask, manual path strategy, CLI overrides, and exact text fields.
- Keep profile browsing and raw editing on separate surfaces. `PresetCard` is for starter-profile selection only; its selection state is semantic, never implied by position or accent color. Summary values use `SettingsRow`, with monospace values for addresses, ports, TTLs, and chains.
- `ModeEditorScreen` is a bounded form with network, relay, engine, and override sections, Save in the top app bar, and a persistent Cancel bottom bar. From the Expanded width class, its intro, recovery, and validation notices move to a supporting pane.
- Relay profile editors put their primary action in the top app bar through `RipDpiTopBarAction` (Save, or Connect in the AmneziaWG editor), and the body states the reason for a disabled Save.
- A secret field stays masked until the user taps the trailing Reveal `RipDpiIconButton`.
- Use `RipDpiConfigTextField` for multi-line or raw config, `RipDpiTextField` for discrete values, and `RipDpiSwitch` for boolean options.
- Validate at the field first and in a banner second. Validation, import, and destructive confirmation surface through `WarningBanner`, a snackbar, or `RipDpiDialog`, never only through placeholder text or a disabled button.

### Onboarding

- Use the intro hero and footer flow for onboarding, biometric, and permission screens.
- Offer persona selection before permissions, ask for each permission only when it is needed, and offer an optional first diagnostics check.
- Use larger spacing and a stronger title treatment while keeping the calm low-chroma personality.
