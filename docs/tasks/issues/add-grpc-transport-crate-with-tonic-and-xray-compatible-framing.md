---
title: Add gRPC transport crate with tonic and Xray-compatible framing
type: task
status: backlog
area: transport
priority: high
owner: unassigned
parent: epic-composable-transport-layer-parity
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add gRPC transport crate with tonic and Xray-compatible framing #repo/RIPDPI #area/transport #status/backlog ⏫

## Summary

Add `ripdpi-transport-grpc` implementing Xray/V2Fly-compatible gRPC as
an outbound transport, using `tonic` for protobuf framing. Today the
only `grpc` reference in the codebase is the string
`"application/grpc"` in `ripdpi-xhttp`'s Content-Type header — not an
actual gRPC implementation.

## Context

Xray's gRPC transport uses a service named `proxy.v2ray.com.Service`
(or `GunService` in some forks) with a single bidirectional-streaming
method `Tun` carrying `Hunk` protobuf messages. Every frame is a
length-prefixed protobuf on top of HTTP/2. The tricky bit is layering
this under a uTLS-spoofed TLS rather than the default `rustls`
connector `tonic` wants.

## Acceptance criteria

- [ ] Crate exposes `GrpcTransport` with a composable `AsyncRead +
    AsyncWrite` surface.
- [ ] Service name is configurable (Xray default, Gun-style fork,
    custom).
- [ ] Protobuf framing uses `prost`; wire format validated against
    an Xray server fixture.
- [ ] Composable over a uTLS-spoofed TLS connector from
    `ripdpi-tls-profiles` (key integration risk — see epic notes).
- [ ] Per-stream multiplexing via HTTP/2 streams, not a single
    bidirectional stream per connection.
- [ ] Health-check frames respected per Xray spec.
- [ ] Subscription parsers (Clash / sing-box JSON) populate gRPC
    fields for applicable profiles.

## Links

- [[Epic - Composable transport layer parity]]
