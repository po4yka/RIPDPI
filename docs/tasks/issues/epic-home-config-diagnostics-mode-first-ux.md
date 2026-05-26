---
title: Redesign Home / Config / Diagnostics tabs for mode-first UX
type: epic
status: backlog
area: ui
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-08
updated: 2026-05-08
---

- [ ] #task Redesign Home / Config / Diagnostics tabs for mode-first UX #repo/RIPDPI #area/epic #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `epic-home-config-diagnostics-mode-first-ux`
- **Verify:** `all child rows in GOAL_LEDGER.md are DONE or BLOCKED`
- **Scope (only modify these + this file + the ledger):** _epic — coordination only; child tasks carry the file scope_
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Goal

Replace the current single-purpose tabs with a mode-first home screen that surfaces three distinct operating modes — local path optimization, remote tunneled outbound, and network diagnostic — as first-class cards. Each card gives status at a glance and a direct enable/launch action without requiring navigation into Config or Diagnostics first.

## Why now

The current Home tab mixes connection state, diagnostics, and history into a single scrollable blob that doesn't match the three distinct user intents: (1) optimize the local path without a remote tunneled outbound profile, (2) connect through a remote tunneled outbound profile, (3) audit the current network path. Users must navigate to Config to understand what mode is active, and to Diagnostics to run a scan. The redesign reduces that to a single screen.

## Key decisions

- Home becomes a read-act surface: 3 cards, each with a summary of current settings and a primary action (toggle or run button).
- Tapping a card's body (not the toggle) navigates to its dedicated configuration or diagnostics screen.
- Config tab is split into two sub-sections: **Local Path Optimization** (method, listen address, DNS, mode) and **Remote Tunneled Outbound** (relay, server credentials, protocol).
- Diagnostics tab is made standalone, while the Home diagnostic card primary action runs the Home analysis workflow in place and the card body/configure affordance opens Diagnostics.
- Existing `MainUiState` / `ConfigUiState` / `DiagnosticsUiState` are extended rather than replaced.

## Scope

| # | Task slug | Description |
|---|---|---|
| 1 | `add-home-mode-state-and-card-ui-models` | HomeMode enum + per-card UiState models |
| 2 | `build-home-mode-summary-card-composable` | Reusable ModeCard composable with toggle/button |
| 3 | `replace-home-screen-with-three-mode-cards` | Swap HomeScreen layout to 3-card list |
| 4 | `wire-home-local-path-optimization-card-actions` | Local path optimization enable/disable + navigate to config |
| 5 | `wire-home-remote-outbound-card-actions` | Remote outbound connect/disconnect + navigate to config |
| 6 | `wire-home-diagnostic-card-run-action` | Run Home analysis from the diagnostic card and open Diagnostics from the card body/configure affordance |
| 7 | `rework-config-tab-add-mode-section-switcher` | Add Local Path Optimization / Remote Tunneled Outbound section switcher to Config |
| 8 | `build-local-path-optimization-config-sub-screen` | Dedicated Local Path Optimization settings screen |
| 9 | `build-remote-outbound-config-sub-screen` | Dedicated Remote Tunneled Outbound settings screen |
| 10 | `rework-diagnostics-tab-consolidate-entry-points` | Consolidate Diagnostics tab, make fully standalone |

## Ship definition

- Home shows 3 cards; each card reflects live state (active/inactive, last scan result).
- Toggling Local Path Optimization or Remote Tunneled Outbound from the Home card starts/stops the respective engine path.
- Tapping "Configure" on any Home card navigates to the correct Config sub-screen.
- Tapping the Diagnostic card "Run" button starts Home analysis in place; tapping the card body/configure opens Diagnostics; results update the card and Home sheets.
- Config tab shows a Local Path Optimization / Remote Tunneled Outbound switcher; each section's settings are fully editable.
- Diagnostics tab runs independently with no required prior Home interaction.
- No regressions in existing detekt, lint, or test baselines.
