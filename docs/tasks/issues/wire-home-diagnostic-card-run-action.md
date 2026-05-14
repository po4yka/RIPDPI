---
title: Wire Home diagnostic card Run button and navigate to Diagnostics tab
type: task
status: backlog
area: ui
priority: medium
owner: unassigned
parent: epic-home-config-diagnostics-mode-first-ux
blocks: []
blocked_by:
  - replace-home-screen-with-three-mode-cards
created: 2026-05-08
updated: 2026-05-08
---

- [ ] #task Wire Home diagnostic card Run button and navigate to Diagnostics tab #repo/RIPDPI #area/ui #status/medium #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `wire-home-diagnostic-card-run-action`
- **Verify:** `just test-module app`
- **Scope (only modify these + this file + the ledger):** `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Connect the Diagnostic card's "Run scan" button on the Home screen to trigger a diagnostics scan, and wire the card body tap to navigate to the Diagnostics tab.

## Context

The diagnostic card is the read/act surface for the middlebox/DPI blocking audit. The "Run scan" button should kick off the same scan that `DiagnosticsViewModel` drives from the Diagnostics tab — either by navigating to the tab and auto-starting, or by posting a shared trigger. The card's `primaryLabel` should display the last scan result summary (e.g. `"HIGH confidence · Apr 19"`) or `"No scan yet"`.

## Acceptance criteria

- [ ] `onDiagnosticRun` in `MainViewModel` navigates to `Route.Diagnostics` and signals `DiagnosticsViewModel` to start a scan immediately (via `SavedStateHandle` argument or shared `ScanTrigger` use-case).
- [ ] `HomeModeCardUiState.diagnosticCard.primaryLabel` reflects the last completed scan: confidence level + date, or `"No scan yet"` if none.
- [ ] `HomeModeCardUiState.diagnosticCard.isActive` is `true` while a scan is in progress (used to show loading state on the card).
- [ ] `onDiagnosticCardClick` navigates to `Route.Diagnostics` without auto-starting a scan.
- [ ] Scan progress does not block the Home screen — the card shows a loading indicator but the rest of the UI remains interactive.
- [ ] After a scan completes and the user returns to Home, the card label updates to the new result.

## Files likely touched

- `app/src/main/kotlin/com/poyka/ripdpi/activities/MainViewModel.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/activities/DiagnosticsViewModel.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/ui/navigation/RipDpiNavHost.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/ui/navigation/Route.kt`

## Links

- [[Epic - Redesign Home / Config / Diagnostics tabs for mode-first UX]]
