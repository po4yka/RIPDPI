# RIPDPI Design System

## Primitives

The shared design system exposed through `RipDpiThemeTokens` is limited to four app-level primitives:

- `colors`: semantic foreground, surface, feedback, and status roles
- `type`: reusable text roles for display, body, meta, and monospace content
- `spacing` and `layout`: global spacing steps plus adaptive layout primitives
- `components`: control metrics such as hit areas, radii, paddings, and switch geometry

Feature geometry does not belong in the global theme. Flow-specific measurements stay close to the screen family that owns them, for example:

- `HomeChromeMetrics`
- `RipDpiIntroScaffoldMetrics`

## Component Taxonomy

- Actions: `RipDpiButton`, `RipDpiIconButton`, `RipDpiChip`
- Inputs: `RipDpiTextField`, `RipDpiConfigTextField`, `RipDpiDropdown`, `RipDpiSwitch`
- Containers: `RipDpiCard`, `PresetCard`, `SettingsRow`, `RipDpiBottomSheet`, `RipDpiDialog`, `RipDpiSnackbar`, `WarningBanner`
- Navigation: `RipDpiTopAppBar`, `BottomNavBar`, `SettingsCategoryHeader`
- Indicators: `StatusIndicator`, `RipDpiPageIndicators`, `LogRow`
- Scaffolds: `RipDpiContentScreenScaffold`, `RipDpiSettingsScaffold`, `RipDpiDashboardScaffold`, `RipDpiIntroScaffold`

## Adaptive Layout Rules

`RipDpiLayout` is width-aware and resolves three size classes:

- `Compact`: under `600dp`
- `Medium`: `600dp` to `839dp`
- `Expanded`: `840dp` and above

These values control:

- content max width
- form max width
- screen horizontal padding
- section and group gaps
- app bar height
- bottom bar height
- grouping behavior (`SingleColumn`, `CenteredColumn`, `SplitColumns`)

Screen rules:

- Forms and settings screens use `RipDpiSettingsScaffold` or `RipDpiContentScreenScaffold` with `Form` width.
- Dashboard and home screens use `RipDpiDashboardScaffold`; expanded layouts should split primary and secondary sections instead of stretching one column edge to edge.
- Intro and auth flows use `RipDpiIntroScaffold`; large font scale must remain unclipped.

## Typography Rules

- `brandMark`: reserved for the home brand header only.
- `brandStatus`: reserved for compact status labels and telemetry badges.
- `sectionTitle`: reserved for section headers and meta grouping labels.
- `caption` and `secondaryBody`: reserved for helper text, nav labels, and secondary metadata.
- `monoValue`, `monoConfig`, `monoInline`, `monoLog`, `monoSmall`: reserved for values, config strings, logs, and dense telemetry.

Reusable primitives must not override typography inline for branding. If a screen needs a special treatment, create a scoped component for that screen family.

## Icon Rules

- Screens must import icons only from `RipDpiIcons`.
- The icon language is outlined Material only.
- Exceptions are allowed only for app assets or illustration drawings, not for action or navigation icons.

## Accessibility Baselines

- Interactive components must expose a semantic hit area of at least `48dp`.
- Buttons, inputs, switches, chips, and icon buttons must keep visible disabled, focused, and error states where applicable.
- Icon-only buttons require a content description.
- `RipDpiDropdown`, `RipDpiTextField`, and `RipDpiSwitch` must surface label and error semantics.
- Secondary text roles must remain readable in both light and dark themes.
- Selectable cards such as `PresetCard` must expose their selection state semantically, not only visually.

## Contributor Rules

- Do not hardcode colors or spacing in screens when an existing token covers the need.
- Do not import raw Material icons into screens or components; add to `RipDpiIcons` first.
- Do not add feature-local geometry to theme tokens.
- Do not introduce a new screen scaffold without reusing an existing scaffold or documenting the exception here.
