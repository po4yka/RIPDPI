---
title: Add HomeMode enum and per-card UiState models for mode-first home screen
type: task
status: review
area: ui
priority: high
owner: unassigned
parent: epic-home-config-diagnostics-mode-first-ux
blocks:
  - build-home-mode-summary-card-composable
blocked_by: []
created: 2026-05-08
updated: 2026-05-08
---

- [ ] #task Add HomeMode enum and per-card UiState models for mode-first home screen #repo/RIPDPI #area/ui #status/review ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-home-mode-state-and-card-ui-models`
- **Verify:** `./gradlew :app:testDebugUnitTest -Pripdpi.skipNativeBuild=true --no-build-cache --no-configuration-cache -Dkotlin.incremental=false --tests 'com.poyka.ripdpi.activities.HomeModeCardUiStateTest'`
- **Scope (only modify these + this file + the ledger):** `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Introduce the state layer that backs the three Home mode cards. This is the data/ViewModel-side prerequisite before any composable work can start.

## Context

`MainUiState` (in `MainViewModel.kt`) currently carries a flat `ConnectionState`, an `HomeApproachSummaryUiState`, and session overview stats. The new design needs a per-mode card model that captures: active state (toggle on/off), a one-line config summary string, and a secondary status label (e.g. last scan date, server name, session duration).

The three modes map to existing engine paths:
- **Local DPI Bypass** — local proxy / local VPN without a remote relay; driven by existing `Mode.LOCAL_VPN` / `Mode.PROXY` paths.
- **VPN with Remote Server** — remote relay enabled; driven by `Mode.LOCAL_VPN` + outbound relay config.
- **Diagnostic** — no engine, just the diagnostics scan runner.

## Acceptance criteria

- [x] `HomeMode` sealed class or enum with three entries: `LocalDpiBypass`, `RemoteVpn`, `Diagnostic`.
- [x] `HomeModeCardUiState` data class per mode (or a common generic with a `HomeMode` discriminator) containing:
  - `isActive: Boolean` — whether the mode's engine is currently running.
  - `primaryLabel: String` — short config summary (e.g. `"tcp: split(host+1) · DoH"` for bypass, server hostname for VPN, last-scan date for diagnostic).
  - `secondaryLabel: String?` — optional extra context (session duration, scan confidence).
  - `isLoading: Boolean` — true while transitioning (start/stop in progress).
- [x] `MainUiState` extended with `localBypassCard: HomeModeCardUiState`, `vpnCard: HomeModeCardUiState`, `diagnosticCard: HomeModeCardUiState`; existing fields preserved.
- [x] `MainViewModel` populates each card model from existing state sources: bypass card from `ConnectionState` + active `ConfigProfile`, VPN card from connection state + relay config, diagnostic card from last `DiagnosticReport`.
- [x] Unit tests for the mapping logic: given a `ConnectionState` + profile, the correct `isActive` and `primaryLabel` values are produced.
- [x] No changes to composable files in this task.

## Files likely touched

- `app/src/main/kotlin/com/poyka/ripdpi/activities/MainUiState.kt` (or wherever `MainUiState` lives)
- `app/src/main/kotlin/com/poyka/ripdpi/activities/MainViewModel.kt`
- New file: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/home/HomeModeCardUiState.kt`

## Links

- [[Epic - Redesign Home / Config / Diagnostics tabs for mode-first UX]]

## Work log

- Added `HomeMode` and `HomeModeCardUiState` models plus `MainUiState.modeCards` and typed accessors for local bypass, remote VPN, and diagnostic cards.
- Wired `buildMainUiState()` to derive mode cards from the effective connection state, configured/active mode, relay settings, connection duration, and latest home diagnostics state.
- Added focused Robolectric unit coverage for stable mode order, proxy/local-VPN bypass activation, remote-relay VPN activation, loading labels, and diagnostic busy/latest-audit labels.
- Verification: `./gradlew :app:testDebugUnitTest -Pripdpi.skipNativeBuild=true --no-build-cache --no-configuration-cache -Dkotlin.incremental=false --tests 'com.poyka.ripdpi.activities.HomeModeCardUiStateTest'` passed.
- Verification: `./gradlew :app:ktlintCheck -Pripdpi.skipNativeBuild=true --no-build-cache --no-configuration-cache -Dkotlin.incremental=false` passed.
- Note: `./gradlew :app:detekt -Pripdpi.skipNativeBuild=true --no-build-cache --no-configuration-cache -Dkotlin.incremental=false` still fails on pre-existing `DiagnosticsUiStateFactory.kt` `InjectConstructorDefaultParameter` findings outside this task.
