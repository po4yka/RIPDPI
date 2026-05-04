---
title: Refactor QUIC and H3 into a composable transport crate
type: task
status: backlog
area: transport
priority: medium
owner: unassigned
parent: epic-composable-transport-layer-parity
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Refactor QUIC and H3 into a composable transport crate #repo/RIPDPI #area/transport #status/backlog 🔼

## Summary

Extract a `ripdpi-transport-quic` crate (and optional H3-specific
facade) so VLESS, VMess, and future outbounds can run over QUIC or
HTTP/3 directly — today QUIC/H3 is protocol-locked inside
`ripdpi-hysteria2` and `ripdpi-masque`.

## Context

Hysteria2 and MASQUE each pull `quinn` + `h3` + `h3-quinn` directly
and use them for their specific protocol needs. VLESS-QUIC, VMess-
QUIC, and generic H3 CONNECT are sing-box-supported outbound shapes
that RIPDPI cannot serve because there's no composable QUIC layer.
Refactor rather than duplicate: move the shared `quinn` setup into a
common crate, keep the Hysteria2 and MASQUE protocol-specific logic
on top.

## Acceptance criteria

- [ ] `ripdpi-transport-quic` exposes `QuicTransport` (bi-directional
    stream) and `QuicDatagramTransport` (CONNECT-UDP / datagram)
    surfaces.
- [ ] Shared `quinn` + `rustls` config factory in the crate;
    Hysteria2 and MASQUE consume it instead of building their own.
- [ ] `ripdpi-hysteria2` and `ripdpi-masque` continue passing all
    existing tests after migration.
- [ ] H3 facade (`H3Transport`) exposes a CONNECT-capable HTTP/3
    surface composable under VLESS / VMess / generic outbounds.
- [ ] ALPN, SNI, and per-profile uTLS-style fingerprinting are
    configurable at the transport boundary.
- [ ] VLESS outbound gains a `transport: quic` mode in its profile
    editor and wire-tests against an Xray VLESS-QUIC server.

## Links

- [[Epic - Composable transport layer parity]]


## control-plane-hardening
