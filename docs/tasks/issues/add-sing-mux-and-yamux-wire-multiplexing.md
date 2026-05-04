---
title: Add sing-mux and yamux wire multiplexing
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

- [ ] #task Add sing-mux and yamux wire multiplexing #repo/RIPDPI #area/transport #status/backlog 🔼

## Summary

Add `ripdpi-transport-mux` implementing the sing-mux (sing-box) and
yamux (hashicorp) wire multiplexing protocols, so multiple logical
streams can share a single outbound connection.

## Context

The existing `ripdpi-relay-mux` crate is session-pooling, not wire-
level multiplexing. NekoBox/sing-box subscriptions frequently request
`mux: sing-mux` or `mux: yamux` on VLESS/VMess/Trojan outbounds to
reduce connection-establishment overhead. `smux` (Trojan-Go only) is
a separate protocol and is out of scope here; add if real Trojan-Go
subscriptions demand it.

## Acceptance criteria

- [ ] Crate implements the sing-mux wire format (frame header, stream
    ID allocation, keepalive); passes upstream test vectors.
- [ ] Crate implements the yamux wire format; passes hashicorp test
    vectors (or a port of them).
- [ ] Common `MuxTransport` trait lets outbounds plug either
    protocol.
- [ ] Configurable limits: max concurrent streams, per-connection
    KB/s target, padding mode (for sing-mux).
- [ ] Backpressure semantics documented; a slow reader on one stream
    does not wedge the whole mux.
- [ ] Benchmark establishing 100 parallel flows: verify the mux
    beats 100-independent-connections on latency and memory; regress
    if it doesn't (and revisit default enable-state).
- [ ] VLESS and Trojan outbound crates gain `mux` config fields and
    compose the transport.

## Links

- [[Epic - Composable transport layer parity]]
