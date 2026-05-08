# Progress

## Current Step

Step 10 - Make Diagnostics tab standalone. Completed.

## Completed Wave

- Step 10
  - Key: `code-assist:home-config-diagnostics-mode-first-ux:step-10:make-diagnostics-tab-standalone`
  - Scope: make Diagnostics runnable from its own tab and from the Home diagnostic card run action without relying on Home-only state.
  - Acceptance: Diagnostics route carries `auto_start_scan`; `DiagnosticsViewModel` handles the saved-state argument once; Dashboard run scan starts the same scan-controller path directly; the old persistent-history notice is removed while remembered networks stay visible.
  - Result: implemented with `Route.Diagnostics(autoStartScan)`, Home run navigation to `Route.Diagnostics(autoStartScan = true)`, `DiagnosticsViewModel.runScan()`, Dashboard run-scan CTA wiring, focused route/nav coverage, and `lastScanSummary` exposure.

- Step 9
  - Key: `code-assist:home-config-diagnostics-mode-first-ux:step-09:build-vpn-config-sub-screen`
  - Scope: replace the VPN summary with a focused config sub-screen that renders relay endpoint, protocol, credentials, and DNS rows, with relay and DNS exposed as actionable rows.
  - Acceptance: VPN config renders relay/protocol/credentials/DNS rows; relay row opens the current-profile editor; DNS row opens DNS settings; focused Config screen coverage passes.
  - Result: implemented with `VpnConfigScreen`, stable VPN config row tags, relay/DNS callback coverage in `ConfigScreenTest`, and a preview.

- Step 8
  - Key: `code-assist:home-config-diagnostics-mode-first-ux:step-08:build-local-bypass-config-sub-screen`
  - Scope: replace the Local DPI Bypass summary with a focused config sub-screen that renders mode, listen address, DNS, and desync rows, with DNS and desync exposed as actionable rows.
  - Acceptance: Local Bypass config renders the expected rows, DNS/desync rows expose click actions, and the selected Config section behavior from Step 7 remains intact.
  - Result: implemented with `LocalBypassConfigScreen`, stable Local Bypass row test tags, and focused coverage in `ConfigScreenTest`.

- Step 7
  - Key: `code-assist:home-config-diagnostics-mode-first-ux:step-07:add-config-mode-section-switcher-and-sub-routes`
  - Scope: add a saveable Config section switcher for Local DPI Bypass vs VPN with Remote Server, seed the correct section from the config sub-routes, and preserve the existing persisted mode selector behavior.
  - Acceptance: `Route.fromStableRoute` resolves Local Bypass and VPN config sub-routes; Config can switch between Local Bypass and VPN summaries without changing stored mode; the selected Config section survives state restoration.
  - Result: implemented with `ConfigModeSection`, a saveable `ConfigModeSectionSwitcher`, route-seeded `ConfigRoute(initialModeSection = ...)`, section-specific summary rows, stable test tags, and focused coverage in `ConfigScreenTest` plus the existing route resolution tests in `RipDpiNavHostLogicTest`.

- `task-1778246859-5d18`
  - Key: `code-assist:home-config-diagnostics-mode-first-ux:step-06:wire-diagnostic-card-actions`
  - Scope: confirm the Diagnostic Scan card primary action runs the existing Home full-analysis path, route diagnostic card body/configure interaction into the Diagnostics Scan section, and derive the card status line from latest audit state.
  - Acceptance: diagnostic primary starts full analysis; card body opens Diagnostics; stale, actionable, failed, and completed audit status lines are short and stable; focused Home card/state tests pass.
  - Result: implemented with `HomeModeCardUiState.diagnosticStatusLine(...)`, Diagnostics Scan initial-section navigation from Home, new home diagnostic status strings, and focused coverage in `HomeModeCardUiStateTest`; existing `HomeScreenTest` and `MainViewModelTest` coverage confirms the card callback and full-analysis path.

- Step 5
  - Key: `code-assist:home-config-diagnostics-mode-first-ux:step-05:wire-vpn-card-actions`
  - Scope: route the VPN with Remote Server primary action through an explicit VPN-specific ViewModel path, preserve VPN consent requirements independent of the configured mode, and send VPN configure/card clicks to a stable config sub-route.
  - Acceptance: VPN starts `Mode.VPN` even when the configured mode remains Proxy; disabling a running VPN session calls the existing service stop path; VPN config route resolves as a non-top-level destination.
  - Result: implemented with `MainViewModel.onToggleVpn`, `Route.VpnConfig`, Home route wiring, route-matcher cleanup, and focused unit coverage in `MainViewModelTest`, `PermissionCoordinatorTest`, and `RipDpiNavHostLogicTest`.

- Step 4
  - Key: `code-assist:home-config-diagnostics-mode-first-ux:step-04:wire-local-bypass-card-actions`
  - Scope: route the Local DPI Bypass primary action through a Proxy-specific ViewModel path, keep VPN consent out of Proxy startup requirements, and send Local Bypass configure/card clicks to a stable config sub-route.
  - Acceptance: Local DPI Bypass starts `Mode.Proxy` even when the configured mode remains VPN; disabling a running Proxy session calls the existing service stop path; Local Bypass config route resolves as a non-top-level destination.
  - Result: implemented with `PermissionAction.StartProxyMode`, `MainViewModel.onToggleLocalBypass`, `Route.LocalBypassConfig`, Home route wiring, and focused unit coverage in `MainViewModelTest`, `PermissionCoordinatorTest`, and `RipDpiNavHostLogicTest`.

- `task-1778244051-6a38`
  - Key: `code-assist:home-config-diagnostics-mode-first-ux:step-03:replace-home-screen-with-three-mode-cards`
  - Scope: replace the legacy Home body with three mode cards, route card/action callbacks from `HomeRoute`, preserve warning banners, and keep diagnostics bottom sheets functional.
  - Acceptance: Home renders exactly one Local DPI Bypass, VPN with Remote Server, and Network Diagnostic card in order; old connection/history/stats/diagnostics body sections are gone; permission warning banners stay above the cards; diagnostic analysis and verification sheets remain usable.
  - Result: implemented in `HomeScreen.kt`, `HomeRoute.kt`, `HomeModeCard.kt`, and `RipDpiTestTags.kt`, with focused coverage in `HomeScreenTest` and updated card body-click semantics in `HomeModeCardTest`.

- `task-1778243237-a5c4`
  - Key: `code-assist:home-config-diagnostics-mode-first-ux:step-02:build-home-mode-card`
  - Scope: add the reusable `HomeModeCard` composable, expose stable mode-card test tags, bridge action/status labels onto the card UI state, and add focused Compose coverage.
  - Acceptance: active/inactive preview states render for all three modes; disabled primary actions expose disabled semantics; tapping the card body does not call the configure callback.
  - Result: implemented in `HomeModeCard.kt` with active/idle/busy status indicators, primary and configure buttons, six previews, and `HomeModeCardTest`.

- `task-1778239088-ea7c`
  - Key: `code-assist:home-config-diagnostics-mode-first-ux:step-01:add-home-mode-state-models`
  - Scope: add `HomeMode` and `HomeModeCardUiState`, extend `MainUiState.modeCards` with a defaulted `ImmutableList`, wire three-card assembly from existing connection and diagnostics state, and add focused state tests.
  - Acceptance: exactly three cards; Local DPI Bypass active iff `Connected` plus `Mode.Proxy`; VPN active iff `Connected` plus `Mode.VPN`; Diagnostic Scan busy iff `homeDiagnostics.analysisAction.busy`.
  - Result: implemented in `MainUiState.modeCards` plus `localBypassCard`, `vpnCard`, and `diagnosticCard` accessors. Mapping is covered by `HomeModeCardUiStateTest`.

## Verification Notes

- 2026-05-08 Codex follow-up:
  - Added the missing `TrackRecomposition("LocalBypassConfigScreen")` call so the new Local Bypass config screen follows the same instrumentation constraint as the VPN config screen.
  - `./gradlew :app:testDebugUnitTest --tests 'com.poyka.ripdpi.activities.DiagnosticsRouteTest' --tests 'com.poyka.ripdpi.ui.navigation.RipDpiNavHostLogicTest' --tests 'com.poyka.ripdpi.ui.screens.config.ConfigScreenTest' --tests 'com.poyka.ripdpi.activities.HomeModeCardUiStateTest' -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed.
  - `./gradlew :app:compileDebugKotlin :app:ktlintCheck -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed.
  - `./gradlew :app:detekt :app:lintDebug -Pripdpi.skipNativeBuild=true --no-daemon --console=plain --continue` still fails only on the previously recorded blockers: `DiagnosticsUiStateFactory.kt` `InjectConstructorDefaultParameter` findings and `values-ru/strings.xml` `customization_icon_*` `ExtraTranslation` errors.
- 2026-05-08 final verification refresh:
  - Feature scan confirmed expected `Route.LocalBypassConfig`, `Route.VpnConfig`, `Route.Diagnostics(autoStartScan)`, `DiagnosticsViewModel.runScan()`, `HomeModeCard`, `LocalBypassConfigScreen`, and `VpnConfigScreen` hooks are present.
  - `./gradlew :app:testDebugUnitTest --tests 'com.poyka.ripdpi.activities.DiagnosticsRouteTest' --tests 'com.poyka.ripdpi.ui.navigation.RipDpiNavHostLogicTest' --tests 'com.poyka.ripdpi.ui.screens.config.ConfigScreenTest' --tests 'com.poyka.ripdpi.activities.HomeModeCardUiStateTest' -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed.
  - `./gradlew :app:testDebugUnitTest -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed.
  - `./gradlew :app:compileDebugKotlin :app:ktlintCheck -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed.
  - `./gradlew :app:detekt :app:lintDebug -Pripdpi.skipNativeBuild=true --no-daemon --console=plain --continue` still fails only on the previously recorded blockers: `DiagnosticsUiStateFactory.kt` `InjectConstructorDefaultParameter` findings and `values-ru/strings.xml` `customization_icon_*` `ExtraTranslation` errors.
- `./gradlew :app:testDebugUnitTest --tests 'com.poyka.ripdpi.activities.DiagnosticsRouteTest' --tests 'com.poyka.ripdpi.ui.navigation.RipDpiNavHostLogicTest' -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed after adding auto-start and Dashboard run-scan coverage.
- `./gradlew :app:compileDebugKotlin :app:ktlintCheck -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` initially failed on new `DiagnosticsViewModel.kt` ktlint indentation/wrapping; `./gradlew :app:ktlintMainSourceSetFormat -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` fixed the formatting, and the rerun passed.
- `./gradlew :app:detekt :app:lintDebug -Pripdpi.skipNativeBuild=true --no-daemon --console=plain --continue` still fails only on pre-existing `DiagnosticsUiStateFactory.kt` `InjectConstructorDefaultParameter` findings and pre-existing `values-ru/strings.xml` `customization_icon_*` `ExtraTranslation` errors; first lint failure remains `customization_icon_default`.
- `./gradlew :app:testDebugUnitTest --tests 'com.poyka.ripdpi.ui.screens.config.ConfigScreenTest' -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed after adding VPN config row rendering and callback coverage.
- `./gradlew :app:compileDebugKotlin :app:ktlintCheck -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed after fixing ktlint when-entry formatting in `VpnConfigScreen.kt`.
- `./gradlew :app:detekt :app:lintDebug -Pripdpi.skipNativeBuild=true --no-daemon --console=plain --continue` still fails only on pre-existing `DiagnosticsUiStateFactory.kt` `InjectConstructorDefaultParameter` findings and pre-existing `values-ru/strings.xml` `customization_icon_*` `ExtraTranslation` errors; first lint failure remains `customization_icon_default`.
- `./gradlew :app:testDebugUnitTest --tests 'com.poyka.ripdpi.ui.screens.config.ConfigScreenTest' -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed after adding Local Bypass row coverage.
- `./gradlew :app:compileDebugKotlin :app:ktlintCheck -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed after fixing import/order formatting.
- `./gradlew :app:detekt :app:lintDebug -Pripdpi.skipNativeBuild=true --no-daemon --console=plain --continue` still fails only on pre-existing `DiagnosticsUiStateFactory.kt` `InjectConstructorDefaultParameter` findings and pre-existing `values-ru/strings.xml` `customization_icon_*` `ExtraTranslation` errors; first lint failure remains `customization_icon_default`.
- `./gradlew :app:testDebugUnitTest --tests 'com.poyka.ripdpi.ui.screens.config.ConfigScreenTest' --tests 'com.poyka.ripdpi.ui.navigation.RipDpiNavHostLogicTest' -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed after removing invalid Compose test imports from the new test file.
- `./gradlew :app:compileDebugKotlin :app:ktlintCheck -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` initially failed only on a new ktlint body-expression formatting issue in `ConfigScreen.kt`; after formatting `ConfigModeSection.fromStableKey(...)`, the rerun passed.
- `./gradlew :app:detekt :app:lintDebug -Pripdpi.skipNativeBuild=true --no-daemon --console=plain --continue` still fails on pre-existing `DiagnosticsUiStateFactory.kt` `InjectConstructorDefaultParameter` findings.
- The same `:app:detekt :app:lintDebug --continue` run still fails lint on pre-existing `values-ru/strings.xml` `ExtraTranslation` errors for `customization_icon_*`; the first reported failure remains `customization_icon_default`.
- `./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.activities.HomeModeCardUiStateTest --tests com.poyka.ripdpi.ui.screens.home.HomeScreenTest --tests 'com.poyka.ripdpi.activities.MainViewModelTest.home full analysis starts automatic audit profile' -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed.
- `./gradlew :app:compileDebugKotlin :app:ktlintCheck -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed.
- `./gradlew :app:detekt :app:lintDebug -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` still fails on pre-existing `DiagnosticsUiStateFactory.kt` `InjectConstructorDefaultParameter` findings; lint continued analysis but the build failed on detekt.
- `./gradlew :app:lintDebug -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` still fails on pre-existing `values-ru/strings.xml` `ExtraTranslation` errors for `customization_icon_*`; the first reported failure is `customization_icon_default`.
- `./gradlew :app:testDebugUnitTest --tests 'com.poyka.ripdpi.activities.MainViewModelTest.vpn toggle*' --tests 'com.poyka.ripdpi.permissions.PermissionCoordinatorTest' --tests 'com.poyka.ripdpi.ui.navigation.RipDpiNavHostLogicTest' -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed.
- `./gradlew :app:compileDebugKotlin :app:ktlintCheck -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed.
- `./gradlew :app:detekt -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` still fails only on pre-existing `DiagnosticsUiStateFactory.kt` `InjectConstructorDefaultParameter` findings outside this wave.
- `./gradlew :app:lintDebug -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` still fails on pre-existing `values-ru/strings.xml` `ExtraTranslation` errors for `customization_icon_*`; the new VPN config title only appears as a missing-translation warning alongside existing untranslated strings.
- `./gradlew :app:testDebugUnitTest --tests 'com.poyka.ripdpi.activities.MainViewModelTest.local bypass toggle*' --tests 'com.poyka.ripdpi.permissions.PermissionCoordinatorTest' --tests 'com.poyka.ripdpi.ui.navigation.RipDpiNavHostLogicTest' -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed.
- `./gradlew :app:compileDebugKotlin :app:ktlintCheck -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed.
- `./gradlew :app:detekt -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` still fails only on pre-existing `DiagnosticsUiStateFactory.kt` `InjectConstructorDefaultParameter` findings outside this wave.
- `./gradlew :app:lintDebug -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` still fails on pre-existing `values-ru/strings.xml` `ExtraTranslation` errors for `customization_icon_*`; the new Local Bypass config title only appears as a missing-translation warning alongside existing untranslated strings.
- An initial grouped focused unit-test run was interrupted after a stop-path test left connection metrics polling active; the test was revised to avoid starting ViewModel observers for that assertion, and the grouped rerun passed.
- `./gradlew :app:testDebugUnitTest --tests 'com.poyka.ripdpi.ui.screens.home.HomeScreenTest' --tests 'com.poyka.ripdpi.ui.screens.home.HomeModeCardTest' -Pripdpi.skipNativeBuild=true` passed.
- `./gradlew :app:ktlintCheck :app:compileDebugKotlin -Pripdpi.skipNativeBuild=true` passed.
- `./gradlew :app:recordRoborazziDebug --tests 'com.poyka.ripdpi.ui.screenshot.RipDpiScreenCatalogScreenshotTest.home*' -Pripdpi.skipNativeBuild=true` passed and refreshed Home screenshot baselines.
- Final post-screenshot `./gradlew :app:ktlintCheck :app:compileDebugKotlin -Pripdpi.skipNativeBuild=true` passed.
- `./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true` still fails only on pre-existing detekt issues outside this wave:
  - `app/src/main/kotlin/com/poyka/ripdpi/activities/DiagnosticsUiStateFactory.kt` `InjectConstructorDefaultParameter`
  - `core/service/src/test/kotlin/com/poyka/ripdpi/services/BaseServiceRuntimeCoordinatorTest.kt` unused test-helper parameters
- `./gradlew :app:detektDebug -Pripdpi.skipNativeBuild=true` is not a valid task in this project.
- An attempted parallel Gradle verification caused a transient Kotlin output-state failure in `:app:compileDebugKotlin`; serialized reruns passed.
- `./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.ui.screens.home.HomeModeCardTest -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed.
- `./gradlew :app:compileDebugKotlin -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed.
- `./gradlew :app:ktlintCheck -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed.
- `./gradlew :app:detekt -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` still fails only on the pre-existing `DiagnosticsUiStateFactory.kt` `InjectConstructorDefaultParameter` findings outside this wave.
- An initial parallel Gradle run caused a transient Kotlin incremental missing-class failure; serialized reruns produced the results above.
- `./gradlew :app:compileDebugKotlin -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed.
- `./gradlew :app:testDebugUnitTest --tests com.poyka.ripdpi.activities.HomeModeCardUiStateTest -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed.
- `./gradlew :app:ktlintCheck -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` passed.
- `./gradlew :app:detekt -Pripdpi.skipNativeBuild=true --no-daemon --console=plain` failed on pre-existing `DiagnosticsUiStateFactory.kt` `InjectConstructorDefaultParameter` findings outside this wave.
- Current worktree had pre-existing unrelated native Rust edits and task-board issue notes before this planning wave.
- Step 1 did not touch `native/rust/`, diagnostics assembler/factory files, or any baseline file.

## Completed Steps

Step 10 - Make Diagnostics tab standalone.
Step 9 - Build VPN config sub-screen.
Step 8 - Build Local Bypass config sub-screen.
Step 7 - Add Config mode section switcher and sub-routes.
Step 6 - Wire Diagnostic card actions.
Step 5 - Wire VPN card actions.
Step 4 - Wire Local DPI Bypass card actions.
Step 3 - Replace Home content with three mode cards.
Step 2 - Build the reusable Home mode card composable.
Step 1 - Add home mode state and card UI models.
