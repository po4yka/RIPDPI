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
| Component metrics | `ui/theme/Spacing.kt` | Control heights, paddings, switch and actuator sizes, badge sizes, row heights |
| Shapes | `ui/theme/Shape.kt` | Rounded-corner token scale and Material 3 shape bridge |
| Motion | `ui/theme/RipDpiMotion.kt` | Durations, scales, easing, spring behavior, reduced-motion handling |
| Surface tokens | `ui/theme/RipDpiSurface.kt` | First-class panel, modal, feedback, menu, and navigation-chrome surfaces |
| State tokens | `ui/theme/RipDpiState.kt` | First-class pressed, focused, disabled, loading, selected, and semantic tone resolution for shared components |

Feature geometry does not belong in the shared theme. Flow-specific measurements stay close to the screen
family that owns them, for example `HomeChromeMetrics` and `RipDpiIntroScaffoldMetrics`.

## Component Taxonomy

- Actions: `RipDpiButton`, `RipDpiIconButton`, `RipDpiChip`
- Connection: `RipDpiConnectionActuator`
- Secure Routes: `RipDpiRouteProfileCard`, `RipDpiRouteCapabilityPill`, `RipDpiRouteStackDiagram`, `RipDpiRouteOpportunityPanel`
- Inputs: `RipDpiTextField`, `RipDpiConfigTextField`, `RipDpiDropdown`, `RipDpiSwitch`
- Containers: `RipDpiCard`, `PresetCard`, `SettingsRow`, `RipDpiBottomSheet`, `RipDpiDialog`, `RipDpiSnackbar`, `WarningBanner`
- Navigation: `RipDpiTopAppBar`, bottom navigation, `SettingsCategoryHeader`
- Indicators: `StatusIndicator`, `RipDpiPageIndicators`, `LogRow`, `StageProgressIndicator`, `AnalysisProgressIndicator`
- Scaffolds: `RipDpiContentScreenScaffold`, `RipDpiSettingsScaffold`, `RipDpiDashboardScaffold`, `RipDpiIntroScaffold`

## Interaction State Layer

Shared components should resolve interaction and semantic states from `RipDpiStateTokens`, not by rebuilding
pressed, focused, disabled, selected, loading, or error colors inline.

Current first-class state families:

- `button`
- `iconButton`
- `textField`
- `chip`
- `switch`
- `settingsRow`
- `banner`
- `actuator`
- `actuatorStage`
- `route`

## Surface Roles

Shared containers should resolve their structural surface from `RipDpiSurfaceTokens` instead of rebuilding
container, border, content, or elevation inline.

Current first-class surface roles:

- Panels: `Card`, `TonalCard`, `ElevatedCard`, `StatusCard`, `SelectedCard`
- Modals: `Dialog`, `BottomSheet`
- Feedback: `Banner`, `Snackbar`
- Chrome: `DropdownMenu`, `BottomBar`, `BottomBarIndicator`, `SwitchThumb`
- Icon badges: `BottomSheetIconBadge`, `DialogIconBadge`, `DialogDestructiveIconBadge`
- Actuator: `ActuatorRail`, `ActuatorCarriage`, `ActuatorTerminalSlot`, `ActuatorPipelineSegment`
- Secure Routes: `RouteProfile`, `RouteCapability`, `RouteStack`, `RouteProvider`, `RouteOpportunity`

Usage rules:

- `StatusCard` is neutral emphasis for important status blocks, not a semantic warning/error container.
- `SelectedCard` is for explicit picked-state surfaces such as presets.
- `Dialog` and `BottomSheet` are separate roles even when they currently share the same visual recipe.
- `SwitchThumb` stays in the surface taxonomy because its elevation and containment are structural, not textual.
- Route roles describe secure-route opportunity and selection surfaces only. They must not imply a new runtime
  protocol, provider integration, or persistence contract until the corresponding product layer exists.

## Component Mapping

| Design concept | Compose entry point | Primary token sources | Visual verification |
|----------------|---------------------|-----------------------|--------------------|
| Primary and secondary actions | `ui/components/buttons/RipDpiButton.kt` | `state.button`, `type.button`, component metrics, `motion` | `RipDpiDesignSystemScreenshotCatalog` |
| Icon-only actions | `ui/components/buttons/RipDpiIconButton.kt` | `state.iconButton`, icon sizes, component metrics, `motion` | `RipDpiDesignSystemScreenshotCatalog` |
| Secure connection actuator | `ui/components/inputs/RipDpiConnectionActuator.kt` | `state.actuator`, `state.actuatorStage`, actuator component metrics, `RipDpiIcons`, `motion` | `RipDpiDesignSystemScreenshotCatalog`, home screenshots |
| Secure route profiles and capabilities | `ui/components/routes/RipDpiRouteComponents.kt` | `state.route`, route surface roles, `type.monoSmall`, `RipDpiIcons` | `RipDpiDesignSystemScreenshotCatalog` |
| Filter and selection chips | `ui/components/inputs/RipDpiChip.kt` | `state.chip`, `type.smallLabel`, `shapes`, component metrics, `motion` | `RipDpiDesignSystemScreenshotCatalog` |
| Text and config fields | `ui/components/inputs/RipDpiTextField.kt` | `state.textField`, `type.monoValue`, `type.monoConfig`, component metrics | `RipDpiDesignSystemScreenshotCatalog` |
| Dropdowns and switches | `ui/components/inputs/RipDpiDropdown.kt`, `ui/components/inputs/RipDpiSwitch.kt` | `state.textField`, `state.switch`, `type`, component metrics | `RipDpiDesignSystemScreenshotCatalog` |
| Cards and selectable presets | `ui/components/cards/RipDpiCard.kt`, `ui/components/cards/PresetCard.kt` | `surfaces`, `layout.cardPadding`, `shapes`, `state` | `RipDpiDesignSystemScreenshotCatalog`, screen catalogs |
| Settings rows | `ui/components/cards/SettingsRow.kt` | `surfaces`, `state.settingsRow`, `type`, component metrics | `RipDpiDesignSystemScreenshotCatalog`, settings screen screenshots |
| Feedback surfaces | `ui/components/feedback/WarningBanner.kt`, `RipDpiDialog.kt`, `RipDpiBottomSheet.kt`, `RipDpiSnackbar.kt` | `surfaces`, `state.banner`, semantic status colors, `type`, layout widths, `motion` | `RipDpiDesignSystemScreenshotCatalog` for banners/snackbars; modal previews and screen catalogs for dialogs/sheets |
| Navigation chrome | `ui/components/navigation/RipDpiTopAppBar.kt`, bottom navigation | `surfaces`, `layout`, `type.appBarTitle`, icon rules, component metrics | home/settings/config screenshots |
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
- `RipDpiDesignSystemScreenshotTest.designSystemCatalogHighContrast`
- `RipDpiScreenCatalogScreenshotTest.homeHighContrastScreen`
- large-font variants such as intro, home, and settings

## Screen Recipes

Home:

- Use `RipDpiDashboardScaffold`.
- Keep a calm status summary above deeper operational detail.
- Use `RipDpiConnectionActuator` as the primary connection primitive in `HomeStatusCard`; it replaces the
  circular VPN power button and owns activation intent, route label, stage pipeline, localized fault, and
  degraded-but-active visual states.
- The actuator route label should name the selected Secure Route, not only the anti-DPI technique. Use Secure
  Route components when a screen needs to compare available VPN, proxy, WARP, relay, provider, or advanced
  technique opportunities.
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

## Layout Recipes

### Two-Column Dashboard Split

- Use `RipDpiDashboardScaffold` as the outer scaffold and prefer `RipDpiAdaptiveColumns` or the equivalent primary/secondary column split for expanded widths.
- Keep the primary column for current state, primary actions, and live remediation. Keep the secondary column for overview, supporting history, or lower-priority context.
- Preserve the same content order when collapsing to one column; compact layouts stack the primary column before the secondary column instead of reinterpreting section meaning.
- Use section gaps and card boundaries to separate concerns rather than adding nested background panels inside each column.
- Do not stretch dashboard cards edge to edge on large screens and do not create a third competing action rail.

### Dense Settings List

- Use `RipDpiSettingsScaffold` for list-heavy settings and `RipDpiContentScreenScaffold` for bounded settings detail flows.
- Structure the body as `SettingsCategoryHeader` followed by one primary `RipDpiCard` or grouped container with `SettingsRow`, fields, or toggles inside.
- Keep section width bounded to form width, preserve stable divider rhythm, and let density come from compact rows rather than reduced hit targets.
- Place banners immediately above the affected category card when needed; banners annotate the list but do not replace the list structure.
- Do not mix full-width free-floating controls, bespoke cards, and row groups inside one category without a clear hierarchy reason.

### Diagnostics Evidence Panel

- Use `RipDpiCard` as the evidence frame and divide evidence into labeled groups with `SettingsCategoryHeader`, `SettingsRow`, `StatusIndicator`, and monospace value roles.
- Put workflow controls, progress, recommendations, and latest evidence in separate card or section boundaries even when they appear in the same diagnostic tab.
- Keep operator-facing facts scannable: labels on the left, stable values on the right or below, and semantic tone only where judgment changes.
- Move session, event, and probe drill-down into `RipDpiBottomSheet` or dialog detail instead of endlessly expanding the main card stack.
- Do not use ungrouped long text dumps or feature-local tinted containers when a shared evidence card structure is available.

### Monospace Log Stream

- Use `RipDpiScreenScaffold` with a quiet overview card and filters above a single dominant `LogsStreamCard`.
- Render every actual entry with `LogRow`; reserve `SettingsRow` for stream controls, binary filters, and summary facts only.
- Keep the stream within one bounded card, using compact spacing, quiet separators, and monospace roles for timestamps, subsystem labels, and payload text.
- Empty or filtered-empty states should appear as dedicated cards occupying the stream slot so the screen layout does not jump.
- Do not wrap each log line in its own card, insert banners between entries, or add loud status color to routine rows.

### Intro Hero + Footer Flow

- Use `RipDpiIntroScaffold` and `rememberRipDpiIntroScaffoldMetrics()` to keep top action, centered hero content, and footer actions aligned across onboarding and permission flows.
- Keep the hero vertically centered with illustration or badge, title, body, and optional inline content, while the footer remains pinned and stable at the bottom.
- Reserve the top action row for skip, dismiss, or lightweight navigation text; primary decisions belong in the footer action cluster.
- Maintain large-font resilience by using bounded intro width, generous vertical gaps, and stable footer spacing instead of shrinking controls.
- Do not push primary actions into the centered hero stack or let the footer float upward unpredictably as content changes.

## Screen Contracts

### Home

- Build with `RipDpiDashboardScaffold` and keep warning or permission banners above the main dashboard content.
- The first primary block must be `HomeStatusCard`; connection state, verification state, and the primary toggle live there and should not be split across multiple cards.
- `HomeStatusCard` must render `RipDpiConnectionActuator` with the stable root tag `home-connection-button`
  and stage tags `home-connection-stage-network`, `home-connection-stage-dns`,
  `home-connection-stage-handshake`, `home-connection-stage-tunnel`, and `home-connection-stage-route`.
- The second primary block must be `HomeDiagnosticsCard`; it owns analysis actions, latest audit summary, remediation ladder, and scan-progress affordances.
- Supporting overview content should stay in secondary cards such as `HomeApproachCard`, `HomeHistoryCard`, and `HomeStatsGrid`.
- The default order is banners, `HomeStatusCard`, `HomeDiagnosticsCard`, optional `HomeApproachCard`, `HomeHistoryCard`, then overview stats.
- Use `WarningBanner`, `RipDpiButton`, `StatusIndicator`, `DiagnosticsRemediationLadderCard`, `StageProgressIndicator`, `AnalysisProgressIndicator`, and `RipDpiBottomSheet` as the default Home primitive set.
- Expanded width should preserve a two-column hierarchy: status and diagnostics on the primary column, overview and history on the secondary column.
- Use `WarningBanner` only for service errors, permission recovery, or background-guidance issues. Do not restyle those concerns as neutral cards.
- Do not introduce decorative hero art, floating metric tiles, alternate action bars above the status card, or inline raw diagnostics tables that belong in Diagnostics or a bottom sheet.

### Diagnostics

- Build with `RipDpiScreenScaffold` and `RipDpiTopAppBar`, then anchor the body with `DiagnosticsSectionSwitcher` followed by section content inside the pager.
- Dashboard, Scan, Tools, Sessions, and Events remain top-level sections. New diagnostics content should attach to one of those sections before introducing another root tab.
- Dashboard should own health summary, active profile context, remembered-network state, and grouped warnings.
- Scan should own profile selection, workflow controls, live progress, latest session, strategy reports, and latest evidence.
- Tools should own approach selection, preview or export actions, and optional debug or performance cards.
- Evidence and recommendations must be grouped in `RipDpiCard`, `WarningBanner`, `SettingsRow`, `StatusIndicator`, `RipDpiChip`, and `RipDpiBottomSheet` compositions rather than raw text blocks.
- Operator-facing artifacts such as candidate ids, domains, resolver names, or transport evidence should use monospace roles and stable row layouts.
- Destructive or high-risk actions must stay behind explicit buttons or dialogs such as `RipDpiDialog`; they must not hide inside row taps.
- Feedback belongs in `RipDpiSnackbarHost` or semantic banners, not inline ad hoc color changes.
- Do not mix scan controls, live evidence, and export tools into one undifferentiated card, and do not build feature-local tone palettes when a shared diagnostic primitive should own the state.

### Logs

- Build with `RipDpiScreenScaffold` and keep the screen visually quiet so the log stream remains dominant.
- The top stack must keep `LogsOverviewCard`, `LogsFiltersSection`, and the stream section in that order.
- Filter controls belong in `SettingsRow`, `RipDpiChip`, and `SettingsCategoryHeader`; `SettingsRow` is for binary filters and compact facts only, not for actual log entries.
- The stream itself should stay inside `LogsStreamCard` and render entries with `LogRow`; preserve monospace rhythm, compact spacing, and divider discipline.
- Empty or filtered-empty states should use a dedicated card such as `LogsEmptyStateCard`, never a raw placeholder string in the list viewport.
- Copy, save, clear, refresh, and support-bundle feedback belongs in snackbar or explicit action rows, not transient inline text.
- Do not add strong background fills, heavy chrome, oversized badges, or banners between individual log rows.

### Settings

- Build top-level settings with `RipDpiSettingsScaffold`; use `RipDpiContentScreenScaffold` only for narrower detail flows that do not behave like the main settings list.
- Compose sections from `SettingsCategoryHeader`, `RipDpiCard`, and `SettingsRow`; a settings section should read as a compact list, not a collection of bespoke cards.
- Keep connectivity, security, personalization, transparency, and maintenance concerns in separate sections with stable ordering.
- A category should resolve to one primary grouped container with stable divider rhythm. Inline banners may precede the affected card, but banners do not replace section structure.
- Use `WarningBanner` for VPN-specific cautions, permission guidance, or stateful advisories. Do not encode those states only through subtitle color.
- Use `RipDpiDropdown`, `RipDpiTextField`, `RipDpiConfigTextField`, and dialogs for editable configuration, confirmation, and credential flows. Do not overload row subtitles with inline form editing.
- Advanced settings should continue the same list-and-section language even when the content becomes dense; density may increase, but the primitive vocabulary should not change.
- Do not introduce alternate typography hierarchies, floating save bars, decorative containers that break the settings rhythm, or multiple banners as a substitute for missing section hierarchy.

### Config

- Treat Config as two coordinated flows: `ConfigScreen` for preset selection and summary, and `ModeEditorScreen` for exact editing.
- `ConfigScreen` should keep this order: CLI warning if present, overview card with active preset and mode chips, presets list, then summary card.
- Preset selection belongs in `PresetCard`; selection state must remain explicit and semantic, never implied only by position or accent color.
- Summary values should use `SettingsRow`, with monospace values for addresses, ports, TTLs, and chain-like strings.
- `ModeEditorScreen` should keep an intro card followed by network, relay, engine, and override sections as needed, all within the same bounded form column.
- `ModeEditorScreen` must use `RipDpiScreenScaffold` with a stable top app bar, a bounded form column, and a persistent two-action bottom bar for cancel and save.
- Use `RipDpiConfigTextField` for multi-line or raw config payloads, `RipDpiTextField` for discrete scalar fields, and `RipDpiSwitch` for boolean options.
- Validation should be field-local first and banner-level second. Validation, import, and destructive confirmation must surface through `WarningBanner`, snackbar, or `RipDpiDialog`; never hide validation only in placeholder text or button disablement.
- Do not merge preset browsing and raw editing into one scrolling surface, and do not reuse `PresetCard` outside preset selection.

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
- shared controls and screens should consume semantic motion presets from `RipDpiMotion` instead of rebuilding raw `tween(...)` and visibility transitions inline
- use the quick/state/emphasized/route tween buckets consistently: quick for borders, alpha, and icon swaps; state for color and content settling; emphasized for section reveal; route for screen navigation
- use shared section enter and exit transitions for expandable settings or form groups before inventing a feature-local visibility animation
- `standardSpring` is for routine press and settle motion, `expressiveSpring` is for selected or emphasized lift, and `motionAwareSpring` is the default when reduced-motion safety matters
- infinite motion is reserved for status or progress affordances that already gate through `allowsInfiniteMotion`; settings, forms, and route chrome should stay finite

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
- Do not rebuild shared panel, modal, menu, or chrome surfaces inline when `RipDpiSurfaceTokens` already owns the role.
- Do not rebuild component state palettes inline when `RipDpiStateTokens` already owns the state family.
- Do not import raw Material icons into screens or components; add to `RipDpiIcons` first.
- Do not add feature-local geometry to theme tokens.
- Do not introduce a new screen scaffold without reusing an existing scaffold or documenting the exception.
- Keep the token names in `DESIGN.md` aligned with `Color.kt`, `Type.kt`, `Spacing.kt`, and `Shape.kt`.

## Drift Checks

The design contract is enforced by:

- `npx --yes @google/design.md lint DESIGN.md`
- `python3 scripts/ci/verify_design_md.py`
- `./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.ui.DesignSystemSourceRulesTest`

These checks cover:

- `DESIGN.md` token names against `Color.kt`, `Type.kt`, `Spacing.kt`, and `Shape.kt`
- required extended headings in `DESIGN.md`
- required implementation guidance headings in this file
- presence of the design-system and theme-variant screenshot tests
- source import boundaries for icons, shared interaction semantics, governed Material primitives, and motion tokens

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
