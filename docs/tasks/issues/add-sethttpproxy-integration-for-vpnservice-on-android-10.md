---
title: Add setHttpProxy integration for VpnService on Android 10+
type: task
status: backlog
area: proxy
priority: low
owner: unassigned
parent: epic-system-http-proxy-service-mode
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [ ] #task Add setHttpProxy integration for VpnService on Android 10+ #repo/RIPDPI #area/proxy #status/backlog 🔽

## Summary

Allow the VpnService builder on Android 10+ to also advertise an HTTP proxy to the system via `setHttpProxy(ProxyInfo.buildDirectProxy(...))`.

## Context

In VPN mode, most traffic goes through TUN. But a handful of apps (and Android system services) honor the system HTTP proxy out-of-band. Setting the proxy to the local mixed inbound port gives those paths a fast-lane without an extra service.

## Acceptance criteria

- [ ] Optional toggle in Advanced Settings: "Also advertise HTTP proxy to system" (default off).
- [ ] When on and API ≥ 29, the VPN builder calls `setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", mixedPort))`.
- [ ] When the mixed port changes, the VPN is NOT auto-reestablished; the toggle change takes effect on next connect.
- [ ] Works only in VPN mode; in Proxy mode, system proxy comes from the user's Android network settings, not us.
- [ ] Bypass list for the system proxy exclusion includes `localhost` and the loopback range.

## Source references

**Reference implementation notes:**:

- `app/src/main/java/io/nekohasekai/sagernet/bg/VpnService.kt` — search for `ProxyInfo.buildDirectProxy`. The VPN builder calls `builder.setHttpProxy(ProxyInfo.buildDirectProxy("127.0.0.1", DataStore.mixedPort))` gated on Android Q (API 29) via `Build.VERSION.SDK_INT >= 29` and `DataStore.appendHttpProxy` toggle.
- `app/src/main/java/io/nekohasekai/sagernet/database/DataStore.kt` — `appendHttpProxy: Boolean` property (default off).

**Adapt:** The API-29 gate, the default-off toggle, the localhost-loopback proxy config. **Skip:** Reference implementation's `mixedPort` coupling — RIPDPI's equivalent is whatever the mixed-inbound task ([[Add mixed SOCKS5 and HTTP CONNECT inbound listener]]) wires up.

## Links

- [[Epic - System HTTP proxy service mode]]


## vpn-fleet-testing-matrix
