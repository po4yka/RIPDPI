---
title: Add service-mode picker to Settings and onboarding
type: task
status: backlog
area: proxy
priority: medium
owner: unassigned
parent: epic-system-http-proxy-service-mode
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add service-mode picker to Settings and onboarding #repo/RIPDPI #area/proxy #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-service-mode-picker-to-settings-and-onboarding`
- **Verify:** `just test-module core:data:settings`
- **Scope (only modify these + this file + the ledger):** `app/**`, `core/data/settings/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Surface the TUN VPN vs System Proxy choice in both Settings and the
onboarding flow, with a clear trade-off explanation.

## Context

The existing onboarding already validates the chosen mode before finish.
Extend it with the new choice and keep the phrasing honest: VPN is
higher coverage but requires TUN permission; Proxy is lower coverage but
no TUN prompt. Default to VPN mode; users must deliberately opt into
Proxy mode.

## Acceptance criteria

- [ ] Settings / Advanced Settings exposes a "Service mode" radio with
    two options: "Full tunnel (VPN)" and "System proxy only".
- [ ] Onboarding asks the same question with a short trade-off blurb
    and a "most users pick Full tunnel" steer.
- [ ] Changing the mode while a session is running prompts for
    reconnect; the UI does not silently restart.
- [ ] Chosen mode is persisted and restored on boot (coordinates with
    [[Epic - Boot autostart and session persistence]]).
- [ ] Mode name localizes correctly in RTL layouts.

## Source references

**Reference implementation notes:**:

- `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt` — `serviceMode: String` property, constants `MODE_VPN` and `MODE_PROXY` defined in `Key.kt`.
- `app/src/main/java/io/nekohasekai/sagernet/ui/SettingsPreferenceFragment.kt` — search for `serviceMode`; the picker is a `ListPreference` bound to `DataStore.configurationStore`.
- `app/src/main/res/xml/global_preferences.xml` — preference XML for the picker.

**Adapt:** The two-mode picker pattern, the mode-change-requires-reconnect UX (reference implementation reloads via broadcast, RIPDPI can do the same via its existing supervisor reload path). **Skip:** Reference implementation's PreferenceFragment XML approach (RIPDPI is Compose).

## Links

- [[Epic - System HTTP proxy service mode]]
