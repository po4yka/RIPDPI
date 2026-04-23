# RIPDPI Design System

`DESIGN.md` at the repository root is the portable, agent-readable summary of the RIPDPI visual identity.
This document is the engineering supplement for implementation details, mappings, and enforcement rules that
the current `DESIGN.md` format does not express strongly enough.

## Source of Truth

Use these sources in order:

1. `DESIGN.md` for the portable design contract that agents can carry across tools
2. `docs/design-system.md` for RIPDPI-specific implementation guidance, mappings, and screen recipes
3. `app/src/main/kotlin/com/poyka/ripdpi/ui/theme/` for canonical Compose token implementation
4. Roborazzi screenshot baselines under `app/src/test/screenshots/` for visual regression verification

When any of these disagree, the Compose theme code and screenshot baselines win.

## Primitive Layers

| Layer | Compose source | What it owns |
|-------|----------------|--------------|
| Colors | `ui/theme/Color.kt` | Semantic background, text, border, feedback, and status roles plus contrast adaptation |
| Typography | `ui/theme/Type.kt` | Geist families, text-role naming, weight hierarchy, and Material 3 mapping |
| Spacing | `ui/theme/Spacing.kt` | 4px-derived spacing scale |
| Layout | `ui/theme/Spacing.kt` | Width classes, max widths, section gaps, scaffold dimensions |
| Component metrics | `ui/theme/Spacing.kt` | Control heights, paddings, switch sizes, badge sizes, row heights |
| Shapes | `ui/theme/Shape.kt` | Rounded-corner token scale and Material 3 shape bridge |
| Motion | `ui/theme/RipDpiMotion.kt` | Durations, scales, easing, spring behavior, reduced-motion handling |

Feature geometry does not belong in the shared theme. Flow-specific measurements stay close to the screen
family that owns them, for example `HomeChromeMetrics` and `RipDpiIntroScaffoldMetrics`.

## Component Taxonomy

- Actions: `RipDpiButton`, `RipDpiIconButton`, `RipDpiChip`
- Inputs: `RipDpiTextField`, `RipDpiConfigTextField`, `RipDpiDropdown`, `RipDpiSwitch`
- Containers: `RipDpiCard`, `PresetCard`, `SettingsRow`, `RipDpiBottomSheet`, `RipDpiDialog`, `RipDpiSnackbar`, `WarningBanner`
- Navigation: `RipDpiTopAppBar`, bottom navigation, `SettingsCategoryHeader`
- Indicators: `StatusIndicator`, `RipDpiPageIndicators`, `LogRow`, `StageProgressIndicator`, `AnalysisProgressIndicator`
- Scaffolds: `RipDpiContentScreenScaffold`, `RipDpiSettingsScaffold`, `RipDpiDashboardScaffold`, `RipDpiIntroScaffold`

## Component Mapping

| Design concept | Compose entry point | Primary token sources | Visual verification |
|----------------|---------------------|-----------------------|--------------------|
| Primary and secondary actions | `ui/components/buttons/RipDpiButton.kt` | `colors`, `type.button`, component metrics, `motion` | `RipDpiDesignSystemScreenshotCatalog` |
| Icon-only actions | `ui/components/buttons/RipDpiIconButton.kt` | `colors`, icon sizes, component metrics | `RipDpiDesignSystemScreenshotCatalog` |
| Filter and selection chips | `ui/components/inputs/RipDpiChip.kt` | `colors`, `type.smallLabel`, `shapes`, component metrics, `motion` | `RipDpiDesignSystemScreenshotCatalog` |
| Text and config fields | `ui/components/inputs/RipDpiTextField.kt` | `colors`, `type.monoValue`, `type.monoConfig`, component metrics | `RipDpiDesignSystemScreenshotCatalog` |
| Dropdowns and switches | `ui/components/inputs/RipDpiDropdown.kt`, `ui/components/inputs/RipDpiSwitch.kt` | `colors`, `type`, component metrics | `RipDpiDesignSystemScreenshotCatalog` |
| Cards and selectable presets | `ui/components/cards/RipDpiCard.kt`, `ui/components/cards/PresetCard.kt` | `colors`, `layout.cardPadding`, `shapes`, surface roles | `RipDpiDesignSystemScreenshotCatalog`, screen catalogs |
| Settings rows | `ui/components/cards/SettingsRow.kt` | `colors`, `type`, component metrics | settings screen screenshots |
| Feedback surfaces | `ui/components/feedback/WarningBanner.kt`, `RipDpiDialog.kt`, `RipDpiBottomSheet.kt`, `RipDpiSnackbar.kt` | semantic status colors, `type`, layout widths, `motion` | design system and screen catalogs |
| Navigation chrome | `ui/components/navigation/RipDpiTopAppBar.kt`, bottom navigation | `layout`, `type.appBarTitle`, icon rules, component metrics | home/settings/config screenshots |
| Status and telemetry indicators | `ui/components/indicators/StatusIndicator.kt`, `LogRow.kt`, progress indicators | semantic colors, monospace roles, component metrics, `motion` | home, diagnostics, logs screenshots |
| Screen scaffolds | `ui/components/scaffold/RipDpiScaffolds.kt` | `layout`, `spacing`, background color | all screen screenshot catalogs |

## Adaptive Layout Rules

`RipDpiLayout` is width-aware and resolves three size classes:

- `Compact`: under `600dp`
- `Medium`: `600dp` to `839dp`
- `Expanded`: `840dp` and above

These values control:

- content max width
- form max width
- dialog and snackbar max width
- screen horizontal padding
- section and group gaps
- app bar height
- bottom bar height
- grouping behavior (`SingleColumn`, `CenteredColumn`, `SplitColumns`)

Screen rules:

- Forms and settings screens use `RipDpiSettingsScaffold` or `RipDpiContentScreenScaffold` with form-width constraints.
- Dashboard and home screens use `RipDpiDashboardScaffold`; expanded layouts split primary and secondary sections instead of stretching one long column.
- Intro and auth flows use `RipDpiIntroScaffold`; large font scale must remain unclipped.

## Theme Variants

RIPDPI supports:

- light theme
- dark theme
- contrast levels `Standard`, `Medium`, and `High`

The design contract is semantic, not palette-branching. Screens should keep the same structure and role
assignment across variants.

Variant rules:

- Light and dark themes preserve the same component hierarchy and density.
- High contrast strengthens muted text, border, and outline relationships instead of recoloring the interface.
- New UI should be reviewed in at least light, dark, and one large-font or high-contrast configuration.

Current screenshot coverage already includes these checks through:

- `RipDpiDesignSystemScreenshotTest`
- `RipDpiScreenCatalogScreenshotTest.homeDarkScreen`
- `RipDpiScreenCatalogScreenshotTest.homeHighContrastScreen`
- large-font variants such as intro, home, and settings

## Screen Recipes

Home:

- Use `RipDpiDashboardScaffold`.
- Keep a calm status summary above deeper operational detail.
- In expanded layouts, split primary status/actions from secondary analysis or supporting content.

Settings and advanced settings:

- Use `RipDpiSettingsScaffold` or `RipDpiContentScreenScaffold` with form-width constraints.
- Compose from `SettingsRow`, `RipDpiCard`, and `SettingsCategoryHeader`.
- Avoid decorative variance between sections; the content density should carry the screen.

Diagnostics:

- Use card and banner groupings to structure evidence and recommendations.
- Prefer monospace roles for low-level values, candidates, and protocol-oriented artifacts.
- Use semantic status color only where the diagnostic state genuinely changes the user decision.

Logs and history:

- Keep the chrome light and the data dominant.
- Use monospace rhythm, compact spacing, and strong but quiet separators.

Config and mode editor:

- Prefer `RipDpiConfigTextField` for raw config strings and `RipDpiTextField` for discrete values.
- Validation and destructive actions must be explicit and visually stable.

Onboarding and permission flows:

- Use `RipDpiIntroScaffold`.
- Keep the centered narrative, stable footer action, and large-font resilience.
- Increase breathing room, not decorative flourish.

## Typography Rules

- `brandMark`: reserved for the home brand header only.
- `brandStatus`: reserved for compact status labels and telemetry badges.
- `sectionTitle`: reserved for section headers and meta grouping labels.
- `caption` and `secondaryBody`: helper text, nav labels, and secondary metadata.
- `monoValue`, `monoConfig`, `monoInline`, `monoLog`, `monoSmall`: values, config strings, logs, and dense telemetry.
- `screenTitleEmphasis` and `bodyEmphasisBold` exist for stronger emphasis when weight promotion is needed.

Reusable primitives must not override typography inline for branding. If a screen needs a special treatment,
create a scoped component for that screen family.

## Motion Baselines

Motion tokens live in `ui/theme/RipDpiMotion.kt`.

- Quick interactions: `120ms`
- State changes: `220ms`
- Emphasized transitions: `320ms`
- Route transitions: `260ms`
- Press scale: `0.98`
- Selection scale: `1.02`
- Emphasis scale: `1.04`

Motion rules:

- prefer calm spring or eased motion over theatrical bounce
- reduced motion must disable infinite motion and clamp emphasis
- preview and screenshot code should remain stable under static-motion mode

## Icon Rules

- Screens must import icons only from `RipDpiIcons`.
- The icon language is outlined Material only.
- Exceptions are allowed only for app assets or illustration drawings, not for action or navigation icons.

## Accessibility Baselines

- Interactive components must expose a semantic hit area of at least `48dp`.
- Buttons, inputs, switches, chips, cards, and icon buttons must keep visible disabled, focused, pressed, loading, and error states where applicable.
- Icon-only buttons require a content description.
- `RipDpiDropdown`, `RipDpiTextField`, and `RipDpiSwitch` must surface label and error semantics.
- Secondary text roles must remain readable in both light and dark themes.
- Selectable cards such as `PresetCard` must expose their selection state semantically, not only visually.
- Large-font layouts must remain usable without clipped content.

## Contributor Rules

- Do not hardcode colors or spacing in screens when an existing token covers the need.
- Do not import raw Material icons into screens or components; add to `RipDpiIcons` first.
- Do not add feature-local geometry to theme tokens.
- Do not introduce a new screen scaffold without reusing an existing scaffold or documenting the exception.
- Keep the token names in `DESIGN.md` aligned with `Color.kt`, `Type.kt`, `Spacing.kt`, and `Shape.kt`.

## Drift Checks

The design contract is enforced by:

- `npx --yes @google/design.md lint DESIGN.md`
- `python3 scripts/ci/verify_design_md.py`

The verifier checks:

- `DESIGN.md` token names against `Color.kt`, `Type.kt`, `Spacing.kt`, and `Shape.kt`
- required extended headings in `DESIGN.md`
- required implementation guidance headings in this file
- presence of the design-system and theme-variant screenshot tests

## Screenshot Testing

Roborazzi screenshot baselines live under `app/src/test/screenshots`.

- Record baselines with `./gradlew :app:recordRoborazziDebug`
- Verify baselines with `./gradlew :app:verifyRoborazziDebug`
- Root shortcuts are available as `./gradlew recordScreenshots` and `./gradlew verifyScreenshots`

The curated suite covers:

- `RipDpiDesignSystemScreenshotCatalog` in compact, dark-medium, and large-font variants
- `RipDpiScreenPreviewCatalog` scenes for home, settings, diagnostics, intro/auth, and supporting flows
- theme-variant coverage including dark and high-contrast states

The screenshot catalog intentionally excludes continuously animated or cursor-driven samples such as pressed
states and blinking text cursors. Those remain available in the richer preview catalog for manual inspection.
