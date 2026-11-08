---
id: PRX-1794125341062166
title: Repair LOC-08 test-only SOCKS5 server contracts
kind: bug
status: doing
area: proxy
priority: high
owner: SOCKS5 repair team
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-08
updated: 2026-11-08
spec_reason: test-only
---

## Goal

Repair findings F1 through F7 from the LOC-08 audit in the test-only SOCKS5 server. Keep the server behind `cfg(test)` and preserve the production client and inbound paths.

## Acceptance criteria

- Auth replies reach buffered transports before the next protocol read.
- Invalid password versions and reserved request bytes cannot reach success.
- Request time limits cover handshake stages without limiting established relays.
- CONNECT replies report the actual outbound endpoint for IPv4 and IPv6.
- The listener initializes UDP reply addresses; invalid generic UDP setup sends failure.
- Authentication metadata identifies the selected method without storing passwords.
- Each finding has a regression test and a separate fix commit.
- Focused tests, formatting, Clippy, independent review, and `just ci-gates` pass before integration.

## Scope and ownership

This change uses `spec_reason: test-only`: the entire server module is excluded from production builds. It adds no production protocol, public client schema, or Android integration. Intentional BIND/GSSAPI/fragmentation limits and optional audit hypotheses stay out of scope.

- Auth writer: F1, F5, F6, and F7; authentication/parser sections of `server/protocol.rs`, related error variants, and auth regression files.
- Endpoint writer: F3 and F4; `server/tcp.rs`, `server/listener.rs`, UDP configuration branch of `protocol.rs`, and endpoint regression files.
- Deadline writer: F2; handshake preparation in `protocol.rs`, request timeout documentation, and deadline regression files.
- Integrator: task records, ownership notes, serialized application of per-finding patches, reviews, validation, and integration.

All writers use separate worktrees. Changes to shared `protocol.rs`, `server.rs`, and error definitions are combined only by the integrator. Writers export one incremental patch per finding and do not commit before the independent combined review. Cargo.lock, manifests, baselines, API snapshots, and production modules are not writer-owned.

## Evidence

Audit: `/Users/npochaev/Downloads/RIPDPI_network_audits/LOC-08.md`. Base commit: `993d6dc5dc062d1e21500c29b23c17c54eddb798`. The initial audit reproduced all seven failures with raw loopback clients. Implementation evidence will record new regression names and command results.
