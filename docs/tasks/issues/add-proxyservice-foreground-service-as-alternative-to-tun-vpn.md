---
title: Add ProxyService foreground service as alternative to TUN VPN
type: task
status: backlog
area: proxy
priority: medium
owner: unassigned
parent: epic-system-http-proxy-service-mode
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [ ] #task Add ProxyService foreground service as alternative to TUN VPN #repo/RIPDPI #area/proxy #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-proxyservice-foreground-service-as-alternative-to-tun-vpn`
- **Verify:** `just test-module core:service`
- **Scope (only modify these + this file + the ledger):** `core/service/**`, `app/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Introduce `RipDpiProxyService` as a foreground-service alternative to `RipDpiVpnService`: runs the mixed inbound and outbound dispatch, but opens no TUN and creates no `vpn_protect` socket server.

## Context

The existing VPN service holds the TUN-centric invariants. A parallel service class keeps those separate; session picker decides which one starts. One-session-at-a-time guard prevents both from racing.

## Acceptance criteria

- [ ] `RipDpiProxyService` extends a `LifecycleService`, not `VpnService`.
- [ ] Foreground-service type is `systemExempted` + `specialUse`; notification channel reused from VPN path or dedicated.
- [ ] Start/stop transitions share the supervisor lifecycle with `RipDpiVpnService`; a mutual-exclusion guard ensures only one of the two runs per session.
- [ ] Switching VPN → Proxy (or vice versa) closes cleanly before the other starts; no socket or route leaks.
- [ ] Diagnostics, logs, and crash reports clearly tag the active mode.
- [ ] Strategy probe and detection checker both work in Proxy mode without a TUN.

## Source references

**Reference implementation notes:**:

- `app/src/main/java/io/nekohasekai/sagernet/bg/ProxyService.kt` — the full class. Extends `Service` (not `VpnService`), implements `BaseService.Interface`. Declared as `foregroundServiceType="systemExempted"` in manifest.
- `app/src/main/java/io/nekohasekai/sagernet/bg/BaseService.kt` — the shared state machine (`Idle → Connecting → Connected → Stopping → Stopped`) both services implement. **Reference the interface** to understand the contract; RIPDPI's `LifecycleService`-based pattern will fit cleanly.
- `app/src/main/AndroidManifest.xml` — the full `<service>` declaration including `process=":bg"` (separate process for the service) and notification-channel wiring.

**Adapt:** The state machine contract, the one-session-at-a-time guard pattern (mutually exclusive with VPN), the `:bg` separate-process pattern (if RIPDPI doesn't already split). **Skip:** reference implementation-specific state constants; RIPDPI has its own supervisor state enum.

## Links

- [[Epic - System HTTP proxy service mode]]
- [[Add mixed SOCKS5 and HTTP CONNECT inbound listener]]
