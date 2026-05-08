---
title: Rework Config tab with Local Bypass / VPN mode section switcher
type: task
status: backlog
area: ui
priority: high
owner: unassigned
parent: epic-home-config-diagnostics-mode-first-ux
blocks:
  - build-local-dpi-bypass-config-sub-screen
  - build-vpn-config-sub-screen
blocked_by: []
created: 2026-05-08
updated: 2026-05-08
---

- [ ] #task Rework Config tab with Local Bypass / VPN mode section switcher #repo/RIPDPI #area/ui #status/backlog ⏫

## Summary

Replace the Config tab's current flat preset list (Recommended / Proxy / Custom) with a two-section top-level switcher: **Local DPI Bypass** and **VPN with Remote Server**. Each section hosts its own settings screen. The active section matches the mode last selected on the Home screen.

## Context

`ConfigScreen.kt` (322 lines) currently shows: a header card with the active preset name, a Local VPN / Local Proxy mode toggle, an "Edit current" button, and a scrollable PRESETS list + CURRENT VALUES table. The new design replaces this with a `TabRow` or segmented control at the top selecting between the two config domains, with the selected section's screen shown below.

`ConfigViewModel` (scoped to `ConfigGraph`) holds `ConfigUiState` and owns the profile/preset logic. It will be extended to hold the selected section and expose sub-screen state.

## Acceptance criteria

- [ ] `ConfigScreen` top bar replaced with a two-item section switcher: "Local Bypass" | "VPN".
- [ ] Switching sections is persisted in `ConfigViewModel` (survives back-stack navigation within the graph).
- [ ] If navigated from a Home card "Configure" tap with a section argument, the correct section is pre-selected.
- [ ] Each section renders its own content area (initially a placeholder composable; real screens land in sibling tasks).
- [ ] The existing `Route.ModeEditor` sub-destination is preserved for cases where it is still referenced.
- [ ] Existing `ConfigUiState` fields are not removed; new `selectedSection: ConfigSection` enum field added.
- [ ] `ConfigSection` enum: `LocalDpiBypass`, `RemoteVpn`.
- [ ] Screenshot baselines updated.

## Files likely touched

- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/config/ConfigScreen.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/activities/ConfigViewModel.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/ui/navigation/Route.kt` (section nav arg)
- `app/src/main/kotlin/com/poyka/ripdpi/ui/navigation/RipDpiNavHost.kt`

## Links

- [[Epic - Redesign Home / Config / Diagnostics tabs for mode-first UX]]
