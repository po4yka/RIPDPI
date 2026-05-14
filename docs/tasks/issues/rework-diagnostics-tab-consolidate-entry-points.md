---
title: Rework Diagnostics tab to be fully standalone with consolidated entry points
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: epic-home-config-diagnostics-mode-first-ux
blocks: []
blocked_by: []
created: 2026-05-08
updated: 2026-05-08
---

- [ ] #task Rework Diagnostics tab to be fully standalone with consolidated entry points #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `rework-diagnostics-tab-consolidate-entry-points`
- **Verify:** `just test-module app`
- **Scope (only modify these + this file + the ledger):** `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Make the Diagnostics tab fully self-contained: runnable from its own tab without any prior Home interaction, and able to receive an auto-start signal when navigated from the Home diagnostic card "Run" button. Clean up any Home-screen coupling in `DiagnosticsViewModel`.

## Context

`DiagnosticsScreen.kt` (668 lines) currently has three internal pages (Dashboard / Scan / Tools) in a `HorizontalPager`. The Dashboard page shows telemetry health, a "Run scan" button, active profile info, and recent activity. The screen works standalone but carries some legacy state (e.g. a "Persistent history moved out of Diagnostics" notice, remembered networks section) that can be simplified.

The Home diagnostic card (from the companion task `wire-home-diagnostic-card-run-action`) needs to navigate here and trigger an auto-start scan. This task wires that entry point and removes any assumptions that diagnostics is only launched from Home.

## Acceptance criteria

- [ ] `DiagnosticsViewModel` accepts an optional `autoStartScan: Boolean` argument via `SavedStateHandle` (nav argument `"auto_start_scan"`); if true, a scan is started immediately after the screen is composed.
- [ ] `Route.Diagnostics` gains an optional `autoStartScan: Boolean` nav argument (default `false`).
- [ ] Dashboard page: remove the "Persistent history moved out of Diagnostics" notice card (no longer needed now that Home is reworked).
- [ ] Dashboard page: the "Run scan" button is always the primary CTA; it is not gated behind any Home connection state.
- [ ] "Remembered Networks" section preserved (it's useful diagnostic data).
- [ ] `DiagnosticsViewModel` exposes `lastScanSummary: String?` (confidence + date) for consumption by `MainViewModel` to populate the Home diagnostic card label.
- [ ] Existing Dashboard / Scan / Tools pager navigation preserved unchanged.
- [ ] Existing `DiagnosticsUiState` fields not removed; only additions.
- [ ] Instrumentation test: navigate to `Route.Diagnostics(autoStartScan = true)` and verify a scan starts within 2 seconds.

## Files likely touched

- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/DiagnosticsScreen.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/activities/DiagnosticsViewModel.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/ui/navigation/Route.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/ui/navigation/RipDpiNavHost.kt`

## Links

- [[Epic - Redesign Home / Config / Diagnostics tabs for mode-first UX]]
