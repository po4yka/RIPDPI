---
title: Wire Home VPN card toggle and configure navigation
type: task
status: backlog
area: ui
priority: high
owner: unassigned
parent: epic-home-config-diagnostics-mode-first-ux
blocks: []
blocked_by:
  - replace-home-screen-with-three-mode-cards
created: 2026-05-08
updated: 2026-05-08
---

- [ ] #task Wire Home VPN card toggle and configure navigation #repo/RIPDPI #area/ui #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `wire-home-vpn-card-actions`
- **Verify:** `just test-module app`
- **Scope (only modify these + this file + the ledger):** `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Connect the VPN with Remote Server card toggle to the existing VPN connect/disconnect flow, and wire the card body tap to navigate to the VPN config sub-screen in the Config tab.

## Context

The VPN path uses `Mode.LOCAL_VPN` with the outbound relay enabled (the relay protocol + server credentials come from `ConfigProfile`). The existing `MainViewModel.connect()` / `disconnect()` path already handles this; the task ensures the card toggle calls the VPN variant specifically, and that the card reflects the correct active state (relay is configured and engine is running).

## Acceptance criteria

- [ ] `onVpnToggle(true)` starts the engine in VPN-with-relay mode; `onVpnToggle(false)` disconnects.
- [ ] Toggle is disabled when Local DPI Bypass is active (no concurrent modes).
- [ ] Toggle is disabled (with a tooltip or subtitle "No server configured") when no outbound relay profile is set up.
- [ ] Loading state shown on the card while connection is being established.
- [ ] `HomeModeCardUiState.vpnCard.primaryLabel` shows the remote server hostname / protocol (e.g. `"relay.example.com · VLESS"`), or `"Not configured"` if no relay profile exists.
- [ ] `onVpnCardClick` navigates to `Route.Config` selecting the VPN section.
- [ ] VPN permission request triggered on toggle-on; reverts on denial.
- [ ] Existing VPN connect/disconnect tests continue to pass.

## Files likely touched

- `app/src/main/kotlin/com/poyka/ripdpi/activities/MainViewModel.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/ui/navigation/RipDpiNavHost.kt`

## Links

- [[Epic - Redesign Home / Config / Diagnostics tabs for mode-first UX]]
