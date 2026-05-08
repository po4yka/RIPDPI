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

## Goal

Replace the current single-purpose tabs with a mode-first home screen that surfaces three distinct operating modes — local DPI bypass, VPN with remote server, and diagnostic scan — as first-class cards. Each card gives status at a glance and a direct enable/launch action without requiring navigation into Config or Diagnostics first.

## Why now

The current Home tab mixes connection state, diagnostics, and history into a single scrollable blob that doesn't match the three distinct user intents: (1) bypass DPI locally without a VPN server, (2) connect through a remote VPN with DPI desync, (3) audit the network for RKN/DPI blocking. Users must navigate to Config to understand what mode is active, and to Diagnostics to run a scan. The redesign reduces that to a single screen.

## Key decisions

- Home becomes a read-act surface: 3 cards, each with a summary of current settings and a primary action (toggle or run button).
- Tapping a card's body (not the toggle) navigates to its dedicated configuration or diagnostics screen.
- Config tab is split into two sub-sections: **Local DPI Bypass** (desync method, listen address, DNS, mode) and **VPN** (outbound relay, server credentials, protocol).
- Diagnostics tab is made standalone — runnable from its own tab or from the Home diagnostic card; no dependency on Home state for entry.
- Existing `MainUiState` / `ConfigUiState` / `DiagnosticsUiState` are extended rather than replaced.

## Scope

| # | Task slug | Description |
|---|---|---|
| 1 | `add-home-mode-state-and-card-ui-models` | HomeMode enum + per-card UiState models |
| 2 | `build-home-mode-summary-card-composable` | Reusable ModeCard composable with toggle/button |
| 3 | `replace-home-screen-with-three-mode-cards` | Swap HomeScreen layout to 3-card list |
| 4 | `wire-home-local-dpi-bypass-card-actions` | Local bypass enable/disable + navigate to config |
| 5 | `wire-home-vpn-card-actions` | VPN connect/disconnect + navigate to config |
| 6 | `wire-home-diagnostic-card-run-action` | Trigger scan run + navigate to Diagnostics tab |
| 7 | `rework-config-tab-add-mode-section-switcher` | Add Local Bypass / VPN section switcher to Config |
| 8 | `build-local-dpi-bypass-config-sub-screen` | Dedicated Local DPI Bypass settings screen |
| 9 | `build-vpn-config-sub-screen` | Dedicated VPN settings screen |
| 10 | `rework-diagnostics-tab-consolidate-entry-points` | Consolidate Diagnostics tab, make fully standalone |

## Ship definition

- Home shows 3 cards; each card reflects live state (active/inactive, last scan result).
- Toggling Local DPI Bypass or VPN from the Home card starts/stops the respective engine path.
- Tapping "Configure" on any Home card navigates to the correct Config sub-screen.
- Tapping the Diagnostic card "Run" button triggers a scan; result updates the card on return.
- Config tab shows a Local Bypass / VPN switcher; each section's settings are fully editable.
- Diagnostics tab runs independently with no required prior Home interaction.
- No regressions in existing detekt, lint, or test baselines.
