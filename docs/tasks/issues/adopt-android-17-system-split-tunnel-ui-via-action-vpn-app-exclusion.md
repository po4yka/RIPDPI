---
title: Adopt Android 17 system split-tunnel UI via ACTION_VPN_APP_EXCLUSION_SETTINGS
type: task
status: backlog
area: routing
priority: medium
owner: unassigned
parent: epic-advanced-routing-rules-and-geoip-enforcement
blocks: []
blocked_by: []
created: 2026-04-25
updated: 2026-04-25
---

- [ ] #task Adopt Android 17 system split-tunnel UI via ACTION_VPN_APP_EXCLUSION_SETTINGS #repo/RIPDPI #area/routing #status/backlog 🔼

## Summary

Android 17 added a system-owned split-tunnel UI: VPN apps fire `ACTION_VPN_APP_EXCLUSION_SETTINGS` and the OS persists user exclusions across reconnects. Wire this from RIPDPI settings so the per-app exclusion state lives in the OS instead of in-app, reducing the risk of exclusion loss on reconnect.

## Research citation

[[ripdpi-android-research-2026-04-25]] §Android platform — Android 17 Beta 3 (2026-03) added the `ACTION_VPN_APP_EXCLUSION_SETTINGS` intent. Apps fire it to delegate per-app exclusion to a persistent OS-managed screen; exclusions survive reconnects. The underlying `VpnService.Builder` allowlist/blocklist API is unchanged — this is a UX standardisation layer on top.

## Acceptance criteria

- [ ] Settings screen on Android 17+ fires `ACTION_VPN_APP_EXCLUSION_SETTINGS` to delegate to OS UI
- [ ] Android < 17 fallback retains in-app exclusion UI
- [ ] Exclusions verified to persist across VPN reconnects (OS-managed state)
- [ ] Manifest declares supported intent for system discovery

## Links

- Project: [[ripdpi-android]]
- Epic: [[Epic - Advanced routing rules and geoip enforcement]]
- Research: [[ripdpi-android-research-2026-04-25]] §Android platform
