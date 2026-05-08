# Progress

## Current Step

Step 4 - Wire Local DPI Bypass card actions.

## Completed Wave

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

Step 3 - Replace Home content with three mode cards.
Step 2 - Build the reusable Home mode card composable.
Step 1 - Add home mode state and card UI models.
