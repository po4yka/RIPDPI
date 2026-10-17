# RIPDPI Design System

`DESIGN.md` at the repository root is the portable, agent-readable summary of the RIPDPI visual identity. This document is the engineering supplement for implementation details, mappings, and enforcement rules that the current `DESIGN.md` format does not express strongly enough.

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
| Component styles | `ui/theme/RipDpiComponentStyles.kt` and family extensions | Typed `CommonStyle` recipes that apply existing RDS values during layout and drawing |

Feature geometry does not belong in the shared theme. Flow-specific measurements stay close to the screen family that owns them, for example `HomeChromeMetrics` and `RipDpiIntroScaffoldMetrics`.

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

Shared components should resolve interaction and semantic states from `RipDpiStateTokens`, not by rebuilding pressed, focused, disabled, selected, loading, or error colors inline.

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

## Compose Component Styles

The RDS style layer uses the experimental Compose Foundation 1.13 `CommonStyle` and `StyleResolver` APIs. `RipDpiThemeTokens.styles` exposes the component catalog. Colors, surfaces, state resolvers, and motion tokens remain the source of values. Styles do not introduce a separate theme or change the design.

Use `visualStyle: CommonStyle = CommonStyle` to customize an eligible RDS component. `RipDpiIconButton.style` still selects its existing variant. Styles apply to the owned shell of a component. They do not change callbacks, enabled/loading rules, minimum touch targets, semantics, haptics, or automation IDs.

```kotlin
@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

// A caller can change an RDS visual property without replacing behavior.
RipDpiButton(
    text = label,
    onClick = onClick,
    visualStyle = CommonStyle { rdsCornerRadius(12.dp) },
)
```

Existing calls keep their original signatures through small `@NonRestartableComposable` overloads. The styled `SettingsRow` overload groups chevron, divider, monospace-value, and test-tag options in `SettingsRowDecoration`. It requires an explicit `visualStyle` to keep legacy overload resolution unambiguous; omitting the style still uses the unchanged legacy API and its empty default. These overloads pass the empty style to the styled implementation. A caller needs the file-local experimental opt-in only when it uses the `CommonStyle` overload. Keep trailing content and action callbacks last in both signatures.

### Resolution and state

The catalog combines the component defaults and its variant on style layer zero. The caller override runs on layer one. RDS default recipes read `state` directly and call the existing pure state resolver. They must not use nested `pressed`, `selected`, or other state helpers, which increase layer priority. Caller styles can use those helpers. This contract guarantees caller priority over RDS defaults; it does not support arbitrary higher-layer default styles.

One `InteractionSource` supplies both the behavior modifier and the style state. Built-in state includes enabled, pressed, focused, selected, and checked. `RipDpiStyleStateKeys` supplies loading, error, empty, compact, expanded, read-only, and active state where the component needs them. A style state never enables or disables behavior by itself.

Cached recipes read current RDS composition locals through the attached resolver. Create and bind a resolver within the same static composition-local provider scope. The adapter publishes current recipes and the composition-local context from `SideEffect`, after node updates are applied. It keeps the resolver and its active animations when a recipe changes. Cached recipes also update when static providers change. A theme, contrast, or motion change must update the attached node. Do not capture a theme value in a long-lived style without updating that style when the value changes.

Bind each resolver exactly once with `styleResolver` or `ripDpiStyleSurface`. Child content can read that resolver in layout/draw callbacks through `ripDpiStyleContent` or `ripDpiStylePadding`; it must not bind the resolver again. Do not resolve properties from Composition before the node is attached. Material `Text` and `Icon` retain explicit typography and colors.

### Typed properties

| DSL | Applied value |
|---|---|
| `rdsBackground(Color/Fill)` | Container fill |
| `rdsBackgroundAlpha(Float)` | Replacement alpha for a solid fill; does not fade children or shadows |
| `rdsBorder(Color, Dp)` | Border color and width; zero means no border |
| `rdsCornerRadius(Dp)` | Numeric radius with the component shape as fallback |
| `rdsContainerAlpha(Float)` | Shell alpha where its appearance modifier is attached |
| `rdsContentScale(Float)` | Content transform, or whole-shell transform for Chip |
| `rdsContentAlpha(Float)` | Content alpha where the component attaches its content modifier |
| `rdsMinHeight(Dp)` | Optional layout minimum; required component targets still apply |
| `rdsContentPadding(start, top, end, bottom)` | Logical padding for components with a style padding modifier |

Only properties used by a component's modifier pipeline affect that component. Cards retain their `paddingValues`; screen geometry is not a style property. Switch `visualStyle` applies to its track, while the thumb has a separate default recipe. Connection actuator `visualStyle` forwards to its primary action. The legacy Dropdown overload keeps its original positional signature and delegates to the primary overload.

Preserve modifier order. Button and IconButton scale only content; Chip scales its painted shell through `ripDpiStyleDrawing`. Its input and semantics coordinates keep the required touch target. Disabled content alpha, Text-only loading alpha, and shadows retain their original positions. Animate numeric corner radii with RDS motion specifications and construct the shape from the resolved number. Do not assume that a whole `Shape` can interpolate. Reduced or disabled motion must use the existing motion token behavior.

### Boundaries and exceptions

- Material hosts keep their explicit APIs: `Surface`, `Scaffold`, modal sheets, menus, tooltips, dividers, text, and icons. This includes the Material shells of banners, dialogs, sheets, snackbars, context menus, degradation strips, export consent, and metric pills. Their owned badges, handles, and stripes can use styles.
- Canvas renderers, telemetry plots, progress drawing, the disconnect countdown, and navigation transitions retain their drawing or animation algorithms. Styles do not own their feature geometry.
- Components that only arrange or delegate to styled RDS controls do not need an extra resolver. Do not add a style node that has no visible effect.
- Keep the experimental opt-in local to affected files. Do not enable inherited text-style flags or use the transitional `Style`, `styleable`, `styleAppearance`, or `stylePlacement` APIs.

Screenshot differences must be reviewed before any separate golden approval. A dependency update or a change in recomposition count does not prove a performance gain. The migration verification record tracks compile, unit, screenshot, device, and three-state profileable performance evidence separately.

## Surface Roles

Shared containers should resolve their structural surface from `RipDpiSurfaceTokens` instead of rebuilding container, border, content, or elevation inline.

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
- Route roles describe secure-route opportunity and selection surfaces only. They must not imply a new runtime protocol, provider integration, or persistence contract until the corresponding product layer exists.

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
- Home mode summary facets use aligned label/value rows at the default font scale and stack each label above its value at `1.3x` and above. Home mode action pairs likewise stack vertically at accessibility font scales instead of weakening the shared one-line button contract.
- Connection-actuator endpoint labels are measured before layout. When compact accessibility layouts cannot keep both labels clear of the carriage, the redundant endpoint text is hidden while the route label, status text, lock icon, and switch semantics preserve the connection meaning.

## Theme Variants

RIPDPI supports:

- light theme
- dark theme
- contrast levels `Standard`, `Medium`, and `High`

The design contract is semantic, not palette-branching. Screens should keep the same structure and role assignment across variants.

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
- Keep warning, permission, and degradation guidance above the mode cards so recovery information precedes mode selection.
- Inside the default-collapsed `Modes & diagnostics` disclosure, use three `HomeModeCard` entries: local path optimization, remote tunneled outbound, and network diagnostic. Each card owns its own title, summary, status, primary action, and configuration affordance.
- Keep the `Modes & diagnostics` disclosure collapsed by default. Its stable automation ID belongs to the clickable header; collapsed/expanded IDs are state markers only and must not replace the stable action selector.
- Do not reintroduce the legacy `HomeStatusCard`, `HomeDiagnosticsCard`, or `HomeStatsGrid` layout; current Home tests intentionally require the mode-card dashboard without those tags.
- Keep diagnostic detail in the Home bottom sheets or the Diagnostics screen. The Home surface may expose the PCAP toggle after the diagnostic card, but it should not recreate the old diagnostics card stack.

Settings and advanced settings:

- Use `RipDpiSettingsScaffold` or `RipDpiContentScreenScaffold` with form-width constraints.
- Compose from `SettingsRow`, `RipDpiCard`, and `SettingsCategoryHeader`.
- Avoid decorative variance between sections; the content density should carry the screen.

Diagnostics:

- Use card and banner groupings to structure evidence and recommendations.
- Show one primary outcome with metadata from the same evidence source. Keep
  historical and technical evidence below it and label missing metadata explicitly.
- Prefer monospace roles for low-level values, candidates, and protocol-oriented artifacts.
- Use semantic status color only where the diagnostic state genuinely changes the user decision.

Logs and history:

- Keep the chrome light and the data dominant.
- Use monospace rhythm, compact spacing, and strong but quiet separators.

Config and mode editor:

- Prefer `RipDpiConfigTextField` for raw config strings and `RipDpiTextField` for discrete values.
- Validation and destructive actions must be explicit and visually stable.
- Lead Config with the saved profile, traffic scope and path. Keep profile choices
  behind Change profile and long guidance behind Configuration details.
- Settings sections are navigation. The main action stops the running mode, or
  starts the saved mode when the service is halted.

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

- Use `RipDpiScreenScaffold` with a compact status and action row above one dominant `LogsStreamCard`. Open filters and secondary actions in explicit sheets.
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
- The main dashboard content is a single ordered mode-card list: local path optimization, remote tunneled outbound, then network diagnostic.
- `HomeModeCard` is the stable Home primitive for connection and diagnostic entry points; route details, current status, loading state, configuration, and the primary action stay inside the relevant mode card. The diagnostic card primary action runs Home analysis in place, while the body/configure affordance opens Diagnostics.
- The default order is banners, degradation strip when present, the three `HomeModeCard` entries, optional diagnostic PCAP toggle, then Home diagnostic bottom-sheet host.
- Do not render `HomeStatusCard`, `HomeDiagnosticsCard`, `HomeStatsGrid`, `HomeApproachCard`, or `HomeHistoryCard` in the current Home dashboard. Their old test tags are reserved for negative regression assertions.
- Use `WarningBanner`, `HomeModeCard`, `RipDpiButton`, `RipDpiSwitch`, `StatusIndicator`, `StageProgressIndicator`, and `RipDpiBottomSheet` as the default Home primitive set.
- Expanded width should preserve the same mode order and avoid reviving the old two-column status/diagnostics split unless the screenshot contract is intentionally changed first.
- Use `WarningBanner` only for service errors, permission recovery, or background-guidance issues. Do not restyle those concerns as neutral cards.
- Do not introduce decorative hero art, floating metric tiles, alternate action bars above the mode-card list, or inline raw diagnostics tables that belong in Diagnostics or a bottom sheet.

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
- Keep a compact status row with Filters and Log actions controls. The stream
  fills the remaining height in the screen's only vertical event list.
- Keep filter controls in a scrollable sheet with an explicit Done action. Save,
  clear and support sharing belong in a separate action sheet.
- Filter controls belong in `SettingsRow`, `RipDpiChip`, and `SettingsCategoryHeader`; `SettingsRow` is for binary filters and compact facts only, not for actual log entries.
- The stream itself should stay inside `LogsStreamCard` and render entries with `LogRow`; preserve monospace rhythm, compact spacing, and divider discipline.
- Empty or filtered-empty states should use a dedicated card such as `LogsEmptyStateCard`, never a raw placeholder string in the list viewport.
- Copy, save, clear and support-bundle feedback belongs in snackbar or explicit
  action rows. Continuous log flows do not need a simulated refresh delay.
- Do not add strong background fills, heavy chrome, oversized badges, or banners between individual log rows.

### Settings

- Build top-level settings with `RipDpiSettingsScaffold`; use `RipDpiContentScreenScaffold` only for narrower detail flows that do not behave like the main settings list.
- Compose sections from `SettingsCategoryHeader`, `RipDpiCard`, and `SettingsRow`; a settings section should read as a compact list, not a collection of bespoke cards.
- Keep four stable top-level groups: Connection, Interface, Privacy, and Support. Put language in Interface, permission repairs and start-on-boot in Connection, data transparency in Privacy, and backup/maintenance in Support. Preserve contextual security warnings and advanced-persona disclosure.
- Show actual available values such as native language, theme, persona, and icon. Navigation-only rows keep an explicit chevron and short intent without a repeated Manage value or an invented count.
- A category should resolve to one primary grouped container with stable divider rhythm. Inline banners may precede the affected card, but banners do not replace section structure.
- Use `WarningBanner` for tunneled-outbound-specific cautions, permission guidance, or stateful advisories. Do not encode those states only through subtitle color.
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
- `body` and its emphasis variants: primary text at `16sp/24sp`.
- `cardTitle`: mode and profile names at `18sp/24sp`.
- Settings descriptions use `secondaryBody` at `14sp/20sp`; `caption` at `12sp/16sp` is for metadata.
- Settings values wrap below the description in the main text column. Trailing switches and chevrons use content width.
- Diagnostics use horizontal Material 3 scrollable tabs at all font scales.
- `caption` and `secondaryBody`: helper text, nav labels, and secondary metadata.
- `monoValue`, `monoConfig`, `monoInline`, `monoLog`, `monoSmall`: values, config strings, logs, and dense telemetry.
- `screenTitleEmphasis` and `bodyEmphasisBold` exist for stronger emphasis when weight promotion is needed.

Reusable primitives must not override typography inline for branding. If a screen needs a special treatment, create a scoped component for that screen family.

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
- Standalone action and navigation icons use `24dp`; smaller roles are for inline markers.
- SettingsRow uses `rows.settingsRowLeadingIconSize` (`32dp`) inside `rows.settingsRowLeadingBadgeSize` (`40dp`). This decorative leading symbol is a local optical adjustment; it is not the default toolbar or navigation icon size. SettingsRow chevrons use `24dp`.
- Feedback, DNS, import, and recovery leading symbols use `RipDpiIconSizes.Large` (`32dp`) in `components.feedback.decorativeBadgeSize` (`40dp`).
- Compact action density keeps `24dp` glyphs. Chip inline glyphs use `18dp`.
- Icon size does not reduce the `48dp` minimum interactive area.
- Exceptions are allowed only for app assets or illustration drawings, not for action or navigation icons.

## Accessibility Baselines

- Interactive components must expose a semantic hit area of at least `48dp`.
- Buttons, inputs, switches, chips, cards, and icon buttons must keep visible disabled, focused, pressed, loading, and error states where applicable.
- Icon-only buttons require a content description.
- `RipDpiDropdown`, `RipDpiTextField`, and `RipDpiSwitch` must surface label and error semantics.
- Secondary text roles must remain readable in both light and dark themes.
- Selectable cards such as `PresetCard` must expose their selection state semantically, not only visually.
- Large-font layouts must remain usable without clipped content or letter-by-letter compression.
- Action labels wrap and grow with the container. A control height is a minimum.
- Choice groups use intrinsic chip widths in a wrapping layout. Do not enforce equal-width chips or fixed chip heights.
- Headers reserve space for controls. Put long facts and status badges below the title when needed.
- Bottom navigation measures complete labels. Keep one row when it fits; use two columns, then one column if required. Two-column fallback uses text-only items with natural height and at least 48dp targets; decorative icons do not consume an extra row. Preserve full labels, destination order, selection, focus, and callbacks. Allow wrapping at extreme widths.
- Diagrams retain readable text and use scrolling when their geometry exceeds the viewport. Do not shrink text with the drawing.
- At large font scale, app-bar navigation and actions occupy a separate row above the complete title. Add this row only when controls exist. The title has no fixed line limit.
- Text styles and Material typography take an explicit locale from the app resource configuration. Cache these styles by language tags and preserve type metrics.
- Sans text uses `Hyphens.Auto` with `LineBreak.Simple`. This permits system hyphenation only when a word exceeds a complete line. It preserves font sizes and resource strings; monospace values retain their line rules. See [Android paragraph guidance](https://developer.android.com/develop/ui/compose/text/style-paragraph#hyphenate-text-split-across-lines).

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
- `./gradlew :app:testGithubFullDebugUnitTest --tests com.poyka.ripdpi.ui.DesignSystemSourceRulesTest`

These checks cover:

- `DESIGN.md` token names against `Color.kt`, `Type.kt`, `Spacing.kt`, and `Shape.kt`
- required extended headings in `DESIGN.md`
- required implementation guidance headings in this file
- presence of the design-system and theme-variant screenshot tests
- source import boundaries for icons, shared interaction semantics, governed Material primitives, and motion tokens

## Screenshot Testing

Roborazzi screenshot baselines live under `app/src/test/screenshots`.

- Record baselines with `./gradlew :app:recordRoborazziGithubFullDebug`
- Verify baselines with `./gradlew :app:verifyRoborazziGithubFullDebug`
- Root shortcuts are available as `./gradlew recordScreenshots` and `./gradlew verifyScreenshots`

The curated suite covers:

- `RipDpiDesignSystemScreenshotCatalog` in compact, dark-medium, and large-font variants
- `RipDpiScreenPreviewCatalog` scenes for home, settings, diagnostics, intro/auth, and supporting flows
- theme-variant coverage including dark and high-contrast states

The screenshot catalog intentionally excludes continuously animated or cursor-driven samples such as pressed states and blinking text cursors. Those remain available in the richer preview catalog for manual inspection.

## Readability revision

The Material 3 readability change updates primary type, icons, settings rows, and diagnostics tabs. The older RDS HTML previews `type-body`, `type-display`, `icon-set`, `components-settings-row`, and `components-tabs` remain reference-only and retain their earlier compact sizes. The current Compose tokens and `DESIGN.md` define this revision.

The app-wide follow-up applies these rules to shared actions and feedback, DNS and relay choices, localized forms, graph headers and diagrams, recovery and import actions, data cards, and history navigation. The relevant RDS previews include `components-warning-banner`, `gesture-long-press-menu`, `components-chips`, `components-accordion`, `components-bottom-nav`, `vpn-dns-picker`, `vpn-relay-picker`, `vpn-throughput-graph`, `vpn-latency-graph`, `vpn-state-machine`, `diagnostic-port-matrix`, `vpn-strategy-import`, `vpn-oom-recovery`, and `android-history`. Their compact HTML layouts remain read-only. Adaptive wrapping and the measured navigation fallback are deliberate accessibility corrections. See [the audit record](design/readability-audit.md).


### Simple analysis consequences

- Name the action as checking and configuring the connection. Explain before
  launch that DNS, bypass and WARP changes can be saved automatically.
- Show applied settings only from the actual outcome. Cancellation, failure or
  an unavailable outcome does not imply that earlier changes were not saved.
- Offer the existing backup/restore surface with reset hidden in Simple. Keep
  all export privacy and import confirmation safeguards. Explain that FULL
  restores its saved settings payload, SHARE contains no settings, network
  memories remain, and Simple startup reselects the bundled connection path.
- Keep this detailed restoration guidance inside backup/restore. The default
  Simple action explanation stays short. This is not exact diagnostic Undo.
- Neutral measurement messages use an information icon. Warning and Critical
  keep warning treatment; bytes or connection state alone do not prove quality.
