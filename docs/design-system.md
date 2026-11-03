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
| Colors | `ui/theme/RipDpiColorSchemes.kt`, `ui/theme/Color.kt`, `ui/theme/SystemContrast.kt` | Six generated Material 3 schemes, RDS roles derived from the selected scheme, fixed status roles, system contrast level, and dynamic color selection |
| Typography | `ui/theme/Type.kt` | Geist families, text-role naming, weight hierarchy, and the full Material 3 baseline and emphasized scale |
| Spacing | `ui/theme/Spacing.kt` | 4px-derived spacing scale |
| Layout | `ui/theme/Spacing.kt` | Width classes, max widths, section gaps, scaffold dimensions |
| Component metrics | `ui/theme/Spacing.kt` | Control heights, paddings, switch and actuator sizes, badge sizes, row heights |
| Shapes | `ui/theme/Shape.kt` | Rounded-corner token scale and the full Material 3 corner scale, including increased tokens |
| Motion | `ui/theme/RipDpiMotion.kt` | Durations, scales, easing, spring behavior, reduced-motion handling, and `RipDpiMotionScheme` for Material components |
| Surface tokens | `ui/theme/RipDpiSurface.kt` | First-class panel, modal, feedback, menu, and navigation-chrome surfaces |
| State tokens | `ui/theme/RipDpiState.kt` | State resolution for the banner, actuator, and route families. Material 3 components resolve their own interaction states |
| Component styles | `ui/theme/RipDpiComponentStyles.kt` and family extensions | Typed `CommonStyle` recipes that apply existing RDS values to owned shells such as surfaces, badges, indicators, and route pieces |

Feature geometry does not belong in the shared theme. Flow-specific measurements stay close to the screen family that owns them, for example `HomeChromeMetrics` and `RipDpiIntroScaffoldMetrics`.

## Component Taxonomy

- Actions: `RipDpiButton`, `RipDpiIconButton`, `RipDpiChip`
- Connection: `RipDpiConnectionActuator`
- Secure Routes: `RipDpiRouteProfileCard`, `RipDpiRouteCapabilityPill`, `RipDpiRouteStackDiagram`, `RipDpiRouteOpportunityPanel`
- Inputs: `RipDpiTextField`, `RipDpiConfigTextField`, `RipDpiDropdown`, `RipDpiSwitch`
- Containers: `RipDpiCard`, `PresetCard`, `SettingsRow`, `RipDpiBottomSheet`, `RipDpiDialog`, `RipDpiSnackbar`, `WarningBanner`
- Navigation: `RipDpiTopAppBar`, bottom navigation, `SettingsCategoryHeader`. Every screen uses the Material top app bar through `RipDpiTopAppBar`, except Home, which keeps its brand header (`HomeTopBar`) as the documented exception.
- Indicators: `StatusIndicator`, `RipDpiPageIndicators`, `LogRow`, `StageProgressIndicator`, `AnalysisProgressIndicator`
- Scaffolds: `RipDpiContentScreenScaffold`, `RipDpiSettingsScaffold`, `RipDpiDashboardScaffold`, `RipDpiIntroScaffold`

## Interaction State Layer

The `RipDpi*` facade components are built on Material 3 components: `Button` and its variants, `IconButton` and its variants, `Switch`, `OutlinedTextField` and `OutlinedSecureTextField`, `DropdownMenu`, `Card`, `Surface`, `ModalBottomSheet`, `Snackbar`, and `SecondaryTabRow`. Material 3 resolves pressed, focused, disabled, and selected states for these components. The facade maps RDS roles into Material color objects (for example `ripDpiButtonColors` and `ripDpiSwitchColors`) and does not rebuild those states inline.

Material 3 ripple state layers are too faint for keyboard focus, so `RipDpiButton`, `RipDpiIconButton` and `RipDpiTextAction` (and so `RipDpiTopBarAction`) add `Modifier.ripDpiFocusRing`: a 2dp ring in ink or paper, whichever holds 3:1 against the container, covered by `RipDpiFocusRingTest`. A text action draws the ring 4dp outside its bounds, because its label sits flush against them. Content on an accent container uses `accentForeground`; `RipDpiRoleContrastTest` asserts 4.5:1 for every surface and state role at all contrast levels.

Components with a state that Material 3 does not model resolve it from `RipDpiStateTokens`, not by rebuilding semantic colors inline.

Current first-class state families:

- `banner`
- `actuator`
- `route`

The actuator stage roles (`actuatorStage`) are exposed through `RipDpiThemeTokens.stateRoles`, not through `RipDpiStateTokens`.

## Compose Component Styles

The RDS style layer uses the experimental Compose Foundation 1.13.0-beta01 `CommonStyle` and `StyleResolver` APIs. The published Compose BOM beta 2026.10.00 pins this API generation; build with Android SDK 37.1. `RipDpiThemeTokens.styles` exposes the component catalog. Colors, surfaces, state resolvers, and motion tokens remain the source of values. Styles do not introduce a separate theme or change the design.

Use `visualStyle: CommonStyle = CommonStyle` to customize an eligible RDS component. Only components that own their shell take this parameter: `PresetCard`, `RipDpiAccordion`, `RipDpiSegmentedButton`, `RipDpiPageIndicators`, `RipDpiStaleDataBadge`, `AnalysisProgressIndicator`, and the Secure Route components. About 20 files still use `CommonStyle` and `ExperimentalFoundationStyleApi`, which include the feature cards, `LogRow`, `RipDpiDegradationStrip`, `RipDpiBottomSheet` (handle), and `RipDpiConnectionActuator` (fault row). `RipDpiButton`, `RipDpiIconButton`, `RipDpiTextField`, `RipDpiDropdown`, `RipDpiSwitch`, `RipDpiChip`, `RipDpiCard`, and `SettingsRow` have no `visualStyle` parameter: they are built on Material 3 components and take their colors from RDS roles. Styles apply to the owned shell of a component. They do not change callbacks, enabled/loading rules, minimum touch targets, semantics, haptics, or automation IDs.

```kotlin
@file:OptIn(androidx.compose.foundation.style.ExperimentalFoundationStyleApi::class)

// A caller can change an RDS visual property without replacing behavior.
PresetCard(
    title = title,
    description = description,
    visualStyle = CommonStyle { rdsCornerRadius(12.dp) },
)
```

Components with a style catalog entry keep their original signatures through small `@NonRestartableComposable` overloads. These overloads pass the empty style to the styled implementation. A caller needs the file-local experimental opt-in only when it uses the `CommonStyle` overload. Keep trailing content and action callbacks last in both signatures.

### Resolution and state

The catalog combines the component defaults and its variant on style layer zero. The caller override runs on layer one. RDS default recipes read `state` directly and call the existing pure state resolver. They must not use nested `pressed`, `selected`, or other state helpers, which increase layer priority. Caller styles can use those helpers. This contract guarantees caller priority over RDS defaults; it does not support arbitrary higher-layer default styles.

One `InteractionSource` supplies both the behavior modifier and the style state. Built-in state includes enabled, pressed, focused, selected, and checked. `RipDpiStyleStateKeys` supplies the expanded and active state that Foundation has no predefined key for. A style state never enables or disables behavior by itself.

Cached recipes read current RDS composition locals through the attached resolver. Create and bind a resolver within the same static composition-local provider scope. The adapter publishes current recipes and the composition-local context from `SideEffect`, after node updates are applied. It keeps the resolver and its active animations when a recipe changes. Cached recipes also update when static providers change. A theme, contrast, or motion change must update the attached node. Do not capture a theme value in a long-lived style without updating that style when the value changes.

Bind each resolver exactly once with `styleResolver` or `ripDpiStyleSurface`. `ripDpiStyleAppearance` applies an already bound resolver and must not bind it again. Do not resolve properties from Composition before the node is attached. Material `Text` and `Icon` retain explicit typography and colors.

### Typed properties

| DSL | Applied value |
|---|---|
| `rdsBackground(Color/Fill)` | Container fill |
| `rdsBackgroundAlpha(Float)` | Replacement alpha for a solid fill; does not fade children or shadows |
| `rdsBorder(Color, Dp)` | Border color and width; zero means no border |
| `rdsCornerRadius(Dp)` | Numeric radius with the component shape as fallback |
| `rdsMinHeight(Dp)` | Optional layout minimum; required component targets still apply |

Only properties used by a component's modifier pipeline affect that component. Screen geometry is not a style property. Preserve modifier order: disabled content alpha and shadows keep their original positions. Animate numeric corner radii with RDS motion specifications and construct the shape from the resolved number. Do not assume that a whole `Shape` can interpolate. Reduced or disabled motion must use the existing motion token behavior.

### Boundaries and exceptions

- Foundation 1.13 beta interpolates spring properties through normalized progress. On an interrupted reverse transition, it carries that progress velocity without scaling it for the new property delta. A component that needs a spring scale or radius keeps a typed `animateFloatAsState` / `animateDpAsState` and lets its style read the animation State during attached resolution at layer zero; caller styles remain at layer one. Color tweens stay in CommonStyle. Disabling motion drops active spring animators and provides the target immediately.

- Material hosts keep their explicit APIs: `Button`, `IconButton`, `Switch`, `OutlinedTextField`, `Card`, `Surface`, `Scaffold`, modal sheets, menus, tooltips, dividers, text, and icons. This includes the Material shells of banners, dialogs, sheets, snackbars, context menus, degradation strips, export consent, and metric pills. Their owned badges, handles, and stripes can use styles.
- Canvas renderers, telemetry plots, progress drawing, and navigation transitions retain their drawing or animation algorithms. Styles do not own their feature geometry.
- App-shell navigation uses `RipDpiNavigationSuiteScaffold` over Material 3 `NavigationSuiteScaffold`: a bar on compact windows, a collapsed rail on medium and expanded windows (expanded rail at 1.2x font scale and above), and an expanded rail on large and extra-large windows. Tabs perform selection haptics through `rememberRipDpiHapticPerformer`. Container and indicator colors come from the `bottomBar` and `bottomBarIndicator` surface roles; item colors come from the theme foreground and muted foreground.
- Components that only arrange or delegate to styled RDS controls do not need an extra resolver. Do not add a style node that has no visible effect.
- Keep the experimental opt-in local to affected files. Do not enable inherited text-style flags or use the transitional `Style`, `styleable`, `styleAppearance`, or `stylePlacement` APIs.

Screenshot differences must be reviewed before any separate golden approval. A dependency update or a change in recomposition count does not prove a performance gain. The migration verification record tracks compile, unit, screenshot, device, and three-state profileable performance evidence separately.

## Surface Roles

Shared containers should resolve their structural surface from `RipDpiSurfaceTokens` instead of rebuilding container, border, content, or elevation inline.

Current first-class surface roles:

- Panels: `Card`, `TonalCard`, `ElevatedCard`, `SelectedCard`
- Modals: `Dialog`, `BottomSheet`
- Feedback: `Banner`, `Snackbar`
- Chrome: `DropdownMenu`, `BottomBar`, `BottomBarIndicator`
- Icon badges: `BottomSheetIconBadge`, `DialogIconBadge`, `DialogDestructiveIconBadge`
- Actuator: `ActuatorRail`, `ActuatorTerminalSlot`, `ActuatorPipelineSegment`
- Secure Routes: `RouteProfile`, `RouteStack`, `RouteProvider`, `RouteOpportunity`

Usage rules:

- One card rule: `surfaceContainer` tonal steps group content, an outline marks only interactive or selected cards, and a shadow marks only hero or selected cards.
- `TonalCard` groups ordinary content (`surfaceContainer`, no shadow; no outline at standard contrast, an `outlineVariant` edge via `tonalCardBorder` at medium and high contrast). A `TonalCard` nested in a `TonalCard` steps up to `surfaceContainerHigh`.
- `Card` (outlined) is for interactive cards. `SelectedCard` marks a picked or focused card.
- `ElevatedCard` is the hero role for the top summary of a screen. Ordinary content never uses it.
- `SelectedCard` is for explicit picked-state surfaces such as presets.
- `Dialog` and `BottomSheet` are separate roles even when they currently share the same visual recipe.
- Route roles describe secure-route opportunity and selection surfaces only. They must not imply a new runtime protocol, provider integration, or persistence contract until the corresponding product layer exists.

## Component Mapping

| Design concept | Compose entry point | Primary token sources | Visual verification |
|----------------|---------------------|-----------------------|--------------------|
| Primary and secondary actions (Primary is neutral ink: `primaryAction` container with `primaryActionForeground` label, full white in dark theme; disabled uses `border` and `mutedForeground`) | `ui/components/buttons/RipDpiButton.kt` | Material 3 `Button` variants with `colors`, `type.button`, component metrics, `motion` | `RipDpiDesignSystemScreenshotCatalog` |
| Icon-only actions | `ui/components/buttons/RipDpiIconButton.kt` | Material 3 `IconButton` variants with `colors`, icon sizes, component metrics, `motion` | `RipDpiDesignSystemScreenshotCatalog` |
| Secure connection actuator | `ui/components/inputs/RipDpiConnectionActuator.kt` | `state.actuator`, `state.actuatorStage`, `RipDpiIcons`, `motion` | `RipDpiDesignSystemScreenshotCatalog`, home screenshots |
| Secure route profiles and capabilities | `ui/components/routes/RipDpiRouteComponents.kt` | `state.route`, route surface roles, `type.monoSmall`, `RipDpiIcons` | `RipDpiDesignSystemScreenshotCatalog` |
| Filter and selection chips | `ui/components/inputs/RipDpiChip.kt` | Material 3 `Surface` with `colors`, `type.smallLabel`, `shapes`, component metrics, `motion` | `RipDpiDesignSystemScreenshotCatalog` |
| Text and config fields | `ui/components/inputs/RipDpiTextField.kt` | Material 3 `OutlinedTextField` and `OutlinedSecureTextField` with `colors`, `type.monoValue`, `type.monoConfig`, component metrics | `RipDpiDesignSystemScreenshotCatalog` |
| Dropdowns and switches | `ui/components/inputs/RipDpiDropdown.kt`, `ui/components/inputs/RipDpiSwitch.kt` | Material 3 `DropdownMenu` and `Switch`, plus a merged-node dropdown field, with `colors`, `type`, component metrics | `RipDpiDesignSystemScreenshotCatalog` |
| Cards and selectable presets | `ui/components/cards/RipDpiCard.kt`, `ui/components/cards/PresetCard.kt` | Material 3 `Card` with `surfaces`, `layout.cardPadding`, `shapes` | `RipDpiDesignSystemScreenshotCatalog`, screen catalogs |
| Settings rows | `ui/components/cards/SettingsRow.kt` | Material 3 `Surface` with `surfaces`, `type`, component metrics | `RipDpiDesignSystemScreenshotCatalog`, settings screen screenshots |
| Feedback surfaces | `ui/components/feedback/WarningBanner.kt`, `RipDpiDialog.kt`, `RipDpiBottomSheet.kt`, `RipDpiSnackbar.kt` | Material 3 `Card`, `Surface`, `ModalBottomSheet`, and `Snackbar` with `surfaces`, `state.banner`, semantic status colors, `type`, layout widths, `motion` | `RipDpiDesignSystemScreenshotCatalog` for banners/snackbars; modal previews and screen catalogs for dialogs/sheets |
| Fixed tab row | `ui/components/inputs/RipDpiTabs.kt` | Material 3 `SecondaryTabRow` with `colors`, `type`. Diagnostics and History use Material scrollable tab rows directly | config screen screenshots |
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
- List-detail applies from the Expanded width class. Settings, History, and Logs show the list pane at the start and the selected item in the detail pane. Diagnostics shows its details in a detail pane. `RipDpiListDetailLayout` draws both panes with a fixed list pane width, and `RipDpiTopAppBar` drops its back arrow while the content shows beside its list. Below Expanded, the list and the detail are separate destinations.
- Home and the mode editor use a supporting pane from the Expanded width class (`RipDpiContentGrouping.SplitColumns`). Below it the same content stacks in the same order.
- Screens that lay out their own scrolling body wrap it in `RipDpiContentWidth` to cap it at the content width of the current size class.
- Editor primary actions sit in the top app bar through `RipDpiTopBarAction`: Save in the mode editor, relay profile editors, and rule editor, and Connect in the AmneziaWG editor. For a disabled Save, `RelayEditorHint` states the reason in the body.
- Intro and auth flows use `RipDpiIntroScaffold`; large font scale must remain unclipped.
- Home mode summary facets use aligned label/value rows at the default font scale and stack each label above its value at `1.3x` and above. Home mode action pairs likewise stack vertically at accessibility font scales instead of weakening the shared one-line button contract.
- Connection-actuator endpoint labels are measured before layout. When compact accessibility layouts cannot keep both labels clear of the carriage, the redundant endpoint text is hidden while the route label, status text, lock icon, and switch semantics preserve the connection meaning.

## Theme Variants

RIPDPI supports:

- light theme
- dark theme
- contrast levels `Standard`, `Medium`, and `High`, which follow the system contrast setting on Android 14 and later
- opt-in dynamic color on Android 12 and later (`dynamic_color_enabled` in AppSettings, off by default)

The design contract is semantic, not palette-branching. Screens should keep the same structure and role assignment across variants.

Variant rules:

- Light and dark themes preserve the same component hierarchy and density.
- Each contrast level selects its generated scheme. High contrast strengthens text, outline, and container relationships instead of recoloring the interface.
- Dynamic color replaces the Material roles and the RDS roles derived from them. Fixed status roles (`success`, `warning`, `info`, `restricted`) stay unchanged; check status containers with dynamic color on.
- Read colors through `RipDpiThemeTokens.colors` or `MaterialTheme.colorScheme` where the source rules allow it; never add a hard-coded neutral color.
- New UI should be reviewed in at least light, dark, one large-font or high-contrast configuration, and with dynamic color on.

Current screenshot coverage already includes these checks through:

- `RipDpiDesignSystemScreenshotTest`
- `RipDpiScreenCatalogScreenshotTest.homeDarkScreen`
- `RipDpiDesignSystemScreenshotTest.designSystemCatalogHighContrast`
- `RipDpiScreenCatalogScreenshotTest.homeHighContrastScreen`
- large-font variants such as intro, home, and settings

## Screen Recipes

`DESIGN.md#screen-recipes` maps each screen to its scaffold, layout recipe, and contract. This file does not repeat them.

## Layout Recipes

The layout recipes live in `DESIGN.md#layout-recipes`. Implementation helpers: `RipDpiAdaptiveColumns` and `RipDpiContentGrouping.SplitColumns` for the dashboard split, and `rememberRipDpiIntroScaffoldMetrics()` for the intro flow, which onboarding, biometric, and VPN permission screens share.

## Screen Contracts

The screen contracts live in `DESIGN.md#screen-contracts`. Change a screen rule there, in one place, and keep its test tags and Roborazzi baselines in step with it.

## Typography Rules

- `brandMark`: reserved for the home brand header only.
- `brandStatus`: reserved for compact status labels and telemetry badges.
- `sectionTitle`: reserved for section headers and meta grouping labels.
- `body` and its emphasis variants: primary text at `16sp/24sp`.
- `cardTitle`: mode and profile names at `18sp/24sp`.
- Settings descriptions use `secondaryBody` at `14sp/20sp` and appear only when they show a value or state; `caption` at `12sp/16sp` is for metadata.
- Settings values wrap below the description in the main text column. Trailing switches and chevrons use content width.
- Diagnostics use horizontal Material 3 scrollable tabs at all font scales.
- `caption` and `secondaryBody`: nav labels, metadata, and state. Never helper text or layout captions (see the product principle in `DESIGN.md`).
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
- The navigation bar keeps one row. At 1.5x font scale and above it hides visible labels and each icon keeps its label as its accessible name. Medium and expanded windows switch from the collapsed rail to the expanded rail at 1.2x font scale, the lowest scale where a collapsed-rail label breaks a word. Preserve full labels, destination order, selection, focus, and callbacks.
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
