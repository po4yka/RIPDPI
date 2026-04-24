# Compose Design System Audit

Date: 2026-04-24
Scope: `:app` Compose UI under `app/src/main/kotlin/com/poyka/ripdpi/ui`

## Summary

Overall score: 7.1 / 10

RIPDPI has a strong Compose design-system foundation: the design contract is documented, the theme exposes a custom token facade over Material 3, shared controls consume state/surface/motion tokens, and the screenshot catalog covers meaningful theme variants. The main risk is not missing primitives. The risk is drift: feature screens can still bypass the shared primitives with raw Material controls, direct `clickable`, feature-local badge surfaces, and unreviewed visual baseline changes.

## Scorecard

| Area | Score | Assessment |
| --- | ---: | --- |
| Token architecture and docs | 9.0 | Strong. `DESIGN.md`, `docs/design-system.md`, and `ui/theme` are aligned and `scripts/ci/verify_design_md.py` reports zero violations. |
| Shared component system | 8.0 | Strong buttons, cards, inputs, feedback, scaffolds, state tokens, surface roles, and motion tokens. A few feature components bypass them. |
| Screen adoption | 6.5 | Most screens use `RipDpiThemeTokens` and shared scaffolds, but diagnostics/home still have ad hoc Material surfaces and raw interaction paths. |
| Accessibility and adaptive behavior | 7.0 | Shared interaction helpers enforce 48dp hit areas and semantics are present in core controls. Raw `clickable`/`Switch` usages weaken consistency. |
| Verification and governance | 5.5 | Unit/source checks exist and pass, but Roborazzi verification currently fails for the design-system catalog and one screen catalog. Source rules only guard icons. |

## Strengths

- `RipDpiTheme` provides a single custom token access point for colors, type, spacing, layout, component metrics, shapes, motion, surfaces, state roles, and contrast level: `app/src/main/kotlin/com/poyka/ripdpi/ui/theme/RipDpiTheme.kt`.
- Primitive tokens are stable and explicit: `RipDpiSpacing`, `RipDpiLayout`, `RipDpiComponents`, `RipDpiTextStyles`, and shape tokens are immutable value objects.
- The component layer is broad enough for the app domain: buttons, icon buttons, chips, text fields, dropdowns, switches, cards, settings rows, snackbars, dialogs, bottom sheets, progress indicators, and scaffolds all exist under `ui/components`.
- `RipDpiInteraction` centralizes minimum interactive size, roles, haptics, and interaction sources for clickable/selectable/toggleable affordances.
- Icon import policy is enforceable. `DesignSystemSourceRulesTest` rejects raw Material icon imports outside `RipDpiIcons.kt`, and current sources pass that focused rule.
- Visual coverage is substantial: 67 `@Preview` entries and 34 checked-in screenshot baselines, including component catalog, dark mode, high contrast, large font, and major screen states.

## Findings

### P1: Design-system screenshots are currently red

`./gradlew :app:verifyRoborazziDebug --no-daemon` failed design-system catalog verification:

- `RipDpiDesignSystemScreenshotTest.designSystemCatalogLightCompact`: changed
- `RipDpiDesignSystemScreenshotTest.designSystemCatalogDarkMedium`: changed
- `RipDpiDesignSystemScreenshotTest.designSystemCatalogLargeFont`: changed
- `RipDpiDesignSystemScreenshotTest.designSystemCatalogHighContrast`: golden missing
- `RipDpiScreenCatalogScreenshotTest.advancedSettingsScreen`: changed, `diff_percentage=0.33735713`

Evidence:

- Tests: `app/src/test/kotlin/com/poyka/ripdpi/ui/screenshot/RipDpiDesignSystemScreenshotTest.kt`
- Generated diffs: `app/build/outputs/roborazzi/*_compare.png`
- Result JSON: `app/build/test-results/roborazzi/debug/results/*RipDpiDesignSystemScreenshotTest*.json`

Impact: the design system has visual drift that is not currently blessed or rejected. The high-contrast catalog is documented and tested but has no committed golden.

Recommended fix: review the compare images, decide whether the new component catalog layout is intentional, then either update the affected baselines or revert the visual change. Add the missing high-contrast golden if the test is intended to remain.

### P2: Home uses raw Material `Switch`

`HomeAnalysisPanels` uses `androidx.compose.material3.Switch` directly for PCAP recording.

Evidence:

- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/home/HomeAnalysisPanels.kt:317`

Impact: this bypasses `RipDpiSwitch`, `RipDpiStateTokens.switch`, shared dimensions, semantic state labels, error handling, test-tag behavior, and haptics. It is a visible component-level design-system escape hatch in a primary screen.

Recommended fix: replace with `RipDpiSwitch`, passing label/helper semantics where appropriate, or add a documented exception if Material switch parity is deliberately required.

### P2: Diagnostics badges and metric pills rebuild shared surface/state ideas locally

Diagnostics uses many raw `Surface` badge/chip implementations driven by `metricPalette()` and local geometry. Some are legitimate domain-specific visualizations, but several are generic enough to be shared primitives.

Evidence:

- Feature-local semantic palette: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsTonePalette.kt:94`
- Raw metric surface with `MaterialTheme.shapes.large`: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsCards.kt:309`
- Raw event badge with `MaterialTheme.shapes.small`: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsCards.kt:1038`
- Repeated raw scan chips: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsScanSection.kt:507`, `:544`, `:564`, `:603`, `:635`

Impact: diagnostics is the most complex operator UI, so local badge proliferation creates the highest chance of tone drift, shape drift, inconsistent large-font behavior, and inconsistent accessibility semantics.

Recommended fix: introduce a shared `RipDpiStatusBadge` or `RipDpiMetricPill` that maps semantic tone to `RipDpiStateTokens`/`RipDpiSurfaceTokens`, then migrate repeated diagnostics chips and event badges. Keep truly chart-specific geometry local.

### P2: Direct `clickable` bypasses shared interaction semantics

Several interactive screen elements use raw `Modifier.clickable` instead of `ripDpiClickable` or a shared component.

Evidence:

- Collapsible diagnostics section: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsCards.kt:133`
- Probe row: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsCards.kt:193`
- Winning candidate card: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsScanSection.kt:1758`
- Strategy probe candidate row: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsScanSection.kt:1879`
- History clear filters action: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/history/HistoryScreen.kt:681`

Impact: raw `clickable` does not apply the shared minimum interactive size, haptic policy, or consistent interaction source handling. Some usages manually add roles or padding, but the behavior is inconsistent.

Recommended fix: migrate to `ripDpiClickable` or wrap these rows in shared row/card primitives with explicit roles and content descriptions.

### P3: Motion tokens are not uniformly consumed

The motion system is well-defined, but some feature code still uses raw `tween(...)` constants for expandable sections and transitions.

Evidence:

- Diagnostics collapsible chevron and content animation: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsCards.kt:122`, `:173`
- Search found 122 animation API usages across UI sources; most shared controls are tokenized, but feature-level raw transitions remain.

Impact: reduced-motion behavior and duration buckets can diverge from `RipDpiMotion`, especially as feature screens evolve.

Recommended fix: use `RipDpiThemeTokens.motion.sectionEnterTransition()`, `sectionExitTransition()`, `quickTween()`, or `stateTween()` for reusable section and state transitions.

### P3: Source governance is narrower than the design contract

The documented contributor rules prohibit raw colors/spacing, raw surfaces, raw state palettes, raw Material icons, and undocumented scaffolds. The current source rule test only enforces raw Material icon imports.

Evidence:

- `app/src/test/kotlin/com/poyka/ripdpi/ui/DesignSystemSourceRulesTest.kt`

Impact: regressions like raw `Switch`, raw `clickable`, raw `MaterialTheme.shapes`, and repeated raw `Surface` chips are discoverable by audit, but not blocked automatically.

Recommended fix: extend source rules with a small allowlist-based scanner for:

- raw Material controls in `ui/screens`
- `MaterialTheme.colorScheme`, `MaterialTheme.typography`, and `MaterialTheme.shapes` outside theme/preview wrappers
- raw `Color(...)` outside `ui/theme`
- raw `clickable` outside low-level component primitives

## Verification

Passed:

- `python3 scripts/ci/verify_design_md.py`
- `./gradlew :app:testDebugUnitTest --tests 'com.poyka.ripdpi.ui.DesignSystemSourceRulesTest' --tests 'com.poyka.ripdpi.ui.theme.RipDpiColorContrastTest' --tests 'com.poyka.ripdpi.ui.theme.RipDpiLayoutTest' --tests 'com.poyka.ripdpi.ui.theme.RipDpiMotionTest' --tests 'com.poyka.ripdpi.ui.theme.RipDpiStateTokensTest' --tests 'com.poyka.ripdpi.ui.theme.RipDpiSurfaceTokensTest' --no-daemon`

Failed:

- `./gradlew :app:verifyRoborazziDebug --no-daemon`

Failure summary: 706 tests completed, 10 failed. Five failures are visual-baseline related and relevant to this audit: four design-system catalog variants and `advancedSettingsScreen`. Five failures are unrelated app/service tests observed in the same Gradle task: two `AppStartupInitializerTest` cases, one `MainStartupSideEffectsCoordinatorTest`, one `SettingsViewModelBootstrapperTest`, and one `StrategyPackServiceTest`.

## Recommended Remediation Order

1. Triage and either bless or revert the Roborazzi diffs, especially the missing high-contrast design-system golden.
2. Replace the raw Home PCAP `Switch` with `RipDpiSwitch`.
3. Add source-rule tests for raw Material controls, raw `clickable`, and raw `MaterialTheme.*` usage in screens.
4. Extract a shared status badge/metric pill primitive and migrate repeated diagnostics badges.
5. Replace feature-local raw tweens with `RipDpiMotion` helpers where transitions are not chart/progress-specific.
