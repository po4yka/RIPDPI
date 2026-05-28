---
title: Epic - System HTTP proxy service mode
type: epic
status: backlog
area: epic
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [ ] #task Epic - System HTTP proxy service mode #repo/RIPDPI #area/epic #status/backlog 🔼

## Goal

Offer a no-VPN "system proxy" service mode for devices where the user wants a local SOCKS5/HTTP listener plus Android's system HTTP proxy handle, but does not want to hand RIPDPI the full TUN file descriptor. Matches Reference implementation's `MODE_PROXY` alternative to `MODE_VPN`.

## Why now

Two concrete deployments want this: (a) Android 10+ users who only need HTTP(S) coverage for a few apps that honor the system proxy, not full-device tunneling; (b) debug/diagnostics sessions where the operator wants to inspect traffic without the TUN taking over DNS. Today RIPDPI has only the TUN path.

## Key decisions

- **Reuse the existing relay supervisor;** service mode is a different front-end (no TUN establish, no `vpn_protect` socket) over the same outbound dispatch.
- **Mixed listener** (SOCKS5 + HTTP CONNECT on one port), same pattern as reference implementation `mixedPort`. Default 2080, user-configurable.
- **System proxy injection is VPN-mode optional,** not a separate mode. Android 10+ can both establish the TUN and advertise a system HTTP proxy; this feature also benefits VPN mode users.
- **No dual-mode.** Service picker in Settings: TUN VPN (default) or System Proxy. Exactly one runs per session.

## Scope

- **In scope:** new `ProxyService` foreground service; mixed SOCKS5+HTTP inbound; service-mode picker in Settings; Android 10+ `setHttpProxy` integration for VPN mode; onboarding update to introduce the choice.
- **Out of scope:** PAC file generation, authenticated SOCKS5 (unauthenticated local-only is sufficient; remote auth is a different security model), SOCKS4.

## Ship definition

- [ ] Settings surface allows picking TUN VPN or System Proxy mode.
- [ ] In System Proxy mode, a single foreground service on the mixed port answers SOCKS5 and HTTP CONNECT from local apps.
- [ ] No TUN file descriptor is opened in System Proxy mode; `vpn_protect` socket is not required.
- [ ] In VPN mode on Android 10+, an optional "also advertise HTTP proxy to system" toggle calls `setHttpProxy(ProxyInfo.buildDirectProxy(...))` on the builder.
- [ ] Service-mode transitions (switching from TUN to proxy and back) shut down cleanly without leaking sockets or routes.
- [ ] Diagnostics run in both modes; strategy probe works without a TUN present.

## Child tasks

- [[Add mixed SOCKS5 and HTTP CONNECT inbound listener]]
- [[Add ProxyService foreground service as alternative to TUN VPN]]
- [[Add setHttpProxy integration for VpnService on Android 10+]]
- [[Add service-mode picker to Settings and onboarding]]

## Dependencies

- Feeds: [[Epic - Boot autostart and session persistence]] — boot autostart must resume the chosen service mode, not default to TUN.

## Risks / open questions

- Many Android apps ignore the system HTTP proxy; be explicit in UX that System Proxy mode is lower-coverage than VPN.
- HTTP CONNECT with TLS interception is out of scope; we proxy CONNECT tunnels only, no cleartext-to-TLS bridging.
- Foreground-service-type must be `systemExempted` + `specialUse`; verify Play Store compatibility if a managed distribution channel is later added.

## Links

- [[ripdpi-android]]
- Child issues: 4
