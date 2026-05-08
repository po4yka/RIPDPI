# Context

## Source

Source type: rough description from Ralph `build.start` event.

Task name: `home-config-diagnostics-mode-first-ux`

Objective: redesign the Home, Config, and Diagnostics tabs around three first-class operating modes:
Local DPI Bypass, VPN with remote server, and Diagnostic Scan.

## Request Summary

The requested UX replaces the current single-purpose Home layout with three mode cards. Each card shows live state and exposes its primary action directly. Config should split into Local Bypass and VPN sections, and Diagnostics should be runnable directly from the Diagnostics tab without prior Home interaction.

The implementation must proceed in the ten task order from the source prompt and use tests or existing test updates before each behavior change.

## Repo Patterns Observed

- `MainUiState` currently lives in `app/src/main/kotlin/com/poyka/ripdpi/activities/MainViewModel.kt` and is a `@Stable` data class with defaulted fields.
- `HomeDiagnosticsUiState` and related models are in `MainViewModel.kt`; diagnostics card state is assembled by `buildHomeDiagnosticsUiState(...)` in `MainHomeDiagnosticsUiState.kt`.
- `MainViewModel.uiState` is built from settings, service status, runtime state, permission state, diagnostics state, and control-plane stores, then delegated through `buildMainUiState(...)`.
- `ConfigUiState` and `ConfigDraft` live in `ConfigDraftSupport.kt`; `ConfigDraft` already exposes `dnsSummary`, `chainSummary`, `relaySummary`, `proxyIp`, `proxyPort`, and `mode`.
- `HomeScreen.kt` currently renders `HomeStatusCard`, `HomeDiagnosticsCard`, `HomeApproachCard`, `HomeHistoryCard`, and `HomeStatsGrid`; it already preserves warning and permission banners above the content.
- `Route.kt` uses a sealed `Route` hierarchy with `topLevel`, `all`, and `fromStableRoute`. New sub-routes should be added to `all`, not `topLevel`.

## Integration Points

- Main state and mode-card assembly: `MainViewModel.kt`, `MainHomeDiagnosticsUiState.kt`, and the existing `buildMainUiState(...)` path.
- Start/stop behavior: `MainConnectionActions.startMode(mode: Mode)` and `MainConnectionActions.stop()`.
- Home UI replacement: `ui/screens/home/HomeScreen.kt` plus the new `HomeModeCard.kt`.
- Config routing and section split: `ui/navigation/Route.kt`, `ui/navigation/RipDpiNavHost.kt`, and `ui/screens/config/ConfigScreen.kt`.
- Diagnostics entry points: `ui/screens/diagnostics/DiagnosticsScreen.kt`, diagnostics route wiring, and `DiagnosticsScanController`.

## Constraints

- Extend `MainUiState`, `ConfigUiState`, and `HomeDiagnosticsUiState`; do not replace them.
- All added state fields need defaults so existing call sites compile unchanged.
- Do not touch `DiagnosticsUiStateAssembler`, `DiagnosticsUiStateFactory`, or any file under `native/rust/`.
- Do not edit any `*baseline*` file.
- No backend calls; use existing `AppSettingsRepository`, `ServiceStateStore`, and `DiagnosticsScanController`.
- New UI models should use `@Immutable` or `@Stable` as appropriate.
- Use `ImmutableList` from `kotlinx.collections.immutable`.
- New screen composables should call `TrackRecomposition("ScreenName")`.
- Add user-facing strings to `app/src/main/res/values/strings.xml`; do not hardcode them in composables.
- Every new composable file must include at least one `@Preview`.

## Ship Criteria

- `HomeScreen` shows exactly three `HomeModeCard` composables.
- Local DPI Bypass card starts/stops `Mode.Proxy`.
- VPN card starts/stops `Mode.VPN`.
- Configure actions navigate to `Route.LocalBypassConfig` and `Route.VpnConfig`.
- Diagnostic primary action triggers `runFullAnalysis()`.
- Diagnostic card body navigates to `Route.Diagnostics`.
- `ConfigScreen` has Local Bypass and VPN sections with editable settings.
- `DiagnosticsScreen` Run is enabled without prior Home interaction.
- `./gradlew :app:compileDebugKotlin`, `./gradlew :app:lintDebug`, and `./gradlew :app:detekt` pass.
- `HomeScreenTest`, new `HomeModeCardsTest`, and `:app:testDebugUnitTest` have no newly broken tests.

## Worktree Note

At planning time the worktree already had unrelated native Rust edits and task-board issue notes. Future builder turns should preserve those changes and avoid touching them unless their current task explicitly requires it.
