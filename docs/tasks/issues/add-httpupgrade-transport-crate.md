---
title: Add HTTPUpgrade transport crate
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

- [ ] #task Add HTTPUpgrade transport crate #repo/RIPDPI #area/transport #status/backlog 🔼

## Summary

Add `ripdpi-transport-httpupgrade` implementing the Xray/V2Fly
`httpupgrade` transport: a minimal HTTP/1.1 Upgrade handshake followed
by a raw bytestream. Used by subscriptions that want HTTP/1.1-looking
traffic without the WebSocket framing overhead.

## Context

HTTPUpgrade is a newer carrier in the sing-box ecosystem — simpler
than WebSocket (no binary framing), cheaper than gRPC (no H2
overhead). Upstream behavior: client sends an HTTP/1.1 `Upgrade:
websocket` (or custom protocol name) with configurable path and
headers; server responds `101 Switching Protocols`; the socket
becomes raw bytes in both directions.

## Acceptance criteria

- [ ] Crate exposes `HttpUpgradeTransport` with `AsyncRead +
    AsyncWrite` on a raw stream after the upgrade completes.
- [ ] Request supports configurable path, host header, extra
    headers, upgrade protocol name.
- [ ] Response validation rejects non-`101` codes with a typed
    error.
- [ ] Composable over any inner stream (raw TCP, TLS, uTLS).
- [ ] Wire format validated against a live Xray server fixture or
    upstream test bench.
- [ ] Subscription parsers populate httpupgrade fields.

## Links

- [[Epic - Composable transport layer parity]]
