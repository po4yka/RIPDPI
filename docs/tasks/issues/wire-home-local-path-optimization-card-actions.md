---
title: Wire Home local path optimization card toggle and configure navigation
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

- [ ] #task Wire Home local path optimization card toggle and configure navigation #repo/RIPDPI #area/ui #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `wire-home-local-path-optimization-card-actions`
- **Verify:** `just test-module app`
- **Scope (only modify these + this file + the ledger):** `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Connect the Local Path Optimization card toggle on the Home screen to the engine start/stop action, and wire the card body tap to navigate to the Local Path Optimization config sub-screen in the Config tab.

## Context

Local path optimization operates in `Mode.LOCAL_VPN` or `Mode.PROXY` without a tunneled outbound. The existing `MainViewModel` has `connect()` / `disconnect()` actions that already drive the VPN service; this task ensures that toggling the bypass card calls the right variant (local-only, no relay). The "configure" navigation goes to `Route.Config` with a route argument or scroll target selecting the Local Bypass section.

## Acceptance criteria

- [ ] `onBypassToggle(true)` in `MainViewModel` starts the engine in local-only mode (relay disabled); `onBypassToggle(false)` stops it.
- [ ] Toggle is disabled (grayed) when VPN mode is active, to prevent concurrent mode conflicts.
- [ ] Toggle shows loading state (`isLoading = true` on the card) while the VPN permission dialog is pending or the service is starting.
- [ ] `onBypassCardClick` navigates to `Route.Config` and selects the Local Bypass section (via nav argument or `SavedStateHandle` signal).
- [ ] VPN permission request flow (if VPN service not yet granted) is triggered automatically on toggle-on; toggle reverts to off if the user denies.
- [ ] `HomeModeCardUiState.localBypassCard.isActive` reflects live engine state (true when service is running in local-only path).
- [ ] Existing connection flow tests continue to pass.

## Files likely touched

- `app/src/main/kotlin/com/poyka/ripdpi/activities/MainViewModel.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/ui/navigation/RipDpiNavHost.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/ui/navigation/Route.kt` (possible nav arg addition)

## Links

- [[Epic - Redesign Home / Config / Diagnostics tabs for mode-first UX]]
