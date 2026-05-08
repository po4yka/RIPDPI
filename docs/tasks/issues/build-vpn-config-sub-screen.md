---
title: Build VPN with Remote Server config sub-screen
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

- [ ] #task Build VPN with Remote Server config sub-screen #repo/RIPDPI #area/ui #status/backlog ⏫

## Summary

Build the dedicated settings screen for the VPN with Remote Server mode, shown in the "VPN" section of the Config tab. Covers outbound relay protocol selection, server credentials, DPI desync settings applied on the VPN path, and DNS.

## Context

VPN mode = Local VPN + outbound relay enabled. The relay config (protocol, server address, credentials, TLS settings) lives in `ConfigProfile.outboundRelay`. Currently this is editable only through `ModeEditorScreen`. The new screen surfaces relay configuration directly in the Config tab VPN section.

The screen must handle the case where no relay profile exists yet ("No server configured — add one to use VPN mode").

## Acceptance criteria

- [ ] `VpnConfigSection` composable hosted in the VPN section of `ConfigScreen`.
- [ ] Empty state: if no relay is configured, show a prominent "Add server" button that opens an add/import flow (can reuse or link to the existing profile/subscription import path).
- [ ] When a relay is configured, show:
  - **Protocol**: display-only chip (VLESS, VLESS+Reality, AmneziaWG, etc.); tapping opens protocol picker / profile editor.
  - **Server**: hostname + port display row; tapping opens edit field.
  - **TLS / transport**: collapsed summary (e.g. `"Reality · chrome"`); tapping opens detail editor.
  - **Desync method**: same picker as Local Bypass screen — applies DPI desync on traffic leaving the VPN tunnel.
  - **DNS**: same DNS selector as Local Bypass screen.
- [ ] "Edit full profile" button navigates to `Route.ModeEditor` (existing editor) for advanced users.
- [ ] Changes persisted via `ConfigViewModel` profile save path.
- [ ] `@Preview` for empty state and configured state.

## Files likely touched

- New: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/config/VpnConfigSection.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/config/ConfigScreen.kt`
- `app/src/main/kotlin/com/poyka/ripdpi/activities/ConfigViewModel.kt`

## Links

- [[Epic - Redesign Home / Config / Diagnostics tabs for mode-first UX]]
