---
title: Build Local Path Optimization config sub-screen with desync and network settings
type: task
status: backlog
area: ui
priority: high
owner: unassigned
parent: epic-home-config-diagnostics-mode-first-ux
blocks: []
blocked_by:
  - rework-config-tab-add-mode-section-switcher
created: 2026-05-08
updated: 2026-05-08
---

- [ ] #task Build Local Path Optimization config sub-screen with desync and network settings #repo/RIPDPI #area/ui #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `build-local-path-optimization-config-sub-screen`
- **Verify:** `just test-module app`
- **Scope (only modify these + this file + the ledger):** `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Build the dedicated settings screen for the Local Path Optimization mode, shown in the "Local Bypass" section of the Config tab. Covers desync method selection, listen address, DNS settings, and mode (Local VPN vs Local Proxy).

## Context

Local path optimization uses the existing `ConfigProfile` fields that are already editable through `ModeEditorScreen`. The new sub-screen surfaces these settings directly in the Config tab section rather than requiring navigation to a separate editor. Settings shown in the current "CURRENT VALUES" table (Mode, DNS settings, Listen address, Desync method) map 1:1 to this screen's fields.

The outbound relay section is deliberately excluded from this screen — it belongs to the VPN sub-screen.

## Acceptance criteria

- [ ] `LocalDpiBypassConfigSection` composable hosted in the Local Bypass section of `ConfigScreen`.
- [ ] Settings rows (all editable inline or via a bottom sheet):
  - **Mode**: segmented toggle `Local VPN` / `Local Proxy`.
  - **Desync method**: dropdown or picker for available `DesyncMode` values (split, disorder, fake, etc.); shows current value.
  - **Listen address**: text field for proxy listen address (shown/editable only when Mode = Local Proxy).
  - **DNS**: selector for DNS preset (System / Encrypted Cloudflare DoH / Encrypted Google DoH / Custom).
- [ ] Preset quick-select row at the top: `Recommended` and `Custom` chips; selecting Recommended applies safe defaults, Custom unlocks all fields.
- [ ] Changes are written to `ConfigViewModel` / `ConfigUiState` immediately; persisted via the existing profile save path.
- [ ] Validation: invalid listen address format shows inline error; unknown desync mode is rejected.
- [ ] All fields reflect current `ConfigUiState` values on first render.
- [ ] `@Preview` for Recommended preset state and Custom preset state.

## Files likely touched

- New: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/config/LocalDpiBypassConfigSection.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/config/ConfigScreen.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/activities/ConfigViewModel.kt`

## Links

- [[Epic - Redesign Home / Config / Diagnostics tabs for mode-first UX]]
