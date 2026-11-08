---
id: PRX-1794125341062166
title: Repair LOC-08 test-only SOCKS5 server contracts
kind: bug
status: done
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
status_detail: F1-F7 regressions, local gates, and independent reviews passed; ready for main integration.
closed_at: "2026-11-08T08:23:32Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "F1-F7 repaired in seven separate fix commits through feacd7304. Owning crate: 63 passed, 2 existing external tests ignored; Clippy, fmt, doctests, locked metadata, ci-gates and independent code/async reviews passed. Server remains test-only; Android and external interoperability not tested."
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

### Verified repairs

| Finding | Commit | Regression evidence |
|---|---|---|
| F1 | `c2a3df3c8` | Buffered METHOD and password status replies complete without caller flush. |
| F2 | `feacd7304` | Seven deadline tests cover incomplete stages, pending auth, one absolute budget, blocked replies, overflow, and established TCP/UDP lifetime. |
| F3 | `ea8d4b9cd` | IPv4/IPv6 BND matches the real target peer endpoint; half-close preserves the response. |
| F4 | `69e5d2e03` | Listener UDP round trips work for IPv4/IPv6 and release both ports; generic missing setup returns failure. |
| F5 | `0bb4bcf61` | Password versions 00/05/ff fail; version 01 succeeds. |
| F6 | `1115496b7` | Reserved bytes fail before address reads; empty domains fail before routing. |
| F7 | `e4cccb029` | Selected-method metadata distinguishes skipped, NoAuth, and Password without storing passwords. |

Each finding has a regression observed failing before its fix. Additional positive guards also passed. The final crate result is 63 passed, 0 failed, and 2 existing external-network tests ignored. Each intermediate fix commit passed the owning crate suite and normal pre-commit hooks. Final Clippy with all targets and warnings denied, workspace format check, and crate doctests passed (one doctest passed; two existing examples ignored).

`just ci-gates` passed architecture health, file and hotspot budgets, runtime and fixture contracts, diagnostics boundaries, route coverage, locale parity, translation export, and Rust API snapshots. The API check retains its normal Linux-owned snapshot skip on macOS. No baseline or snapshot was changed.

Independent code review found no actionable findings. Independent async review found no remaining actionable findings after the borrowed-transport and authentication-future drop contracts were documented. The committed crate matches the reviewed combined tree. The server remains under `cfg(test)`; production clients, production inbound handling, dependencies, and Cargo.lock are unchanged. Android/device and external-client compatibility were not tested. Intentional unsupported modes and optional audit hypotheses were not implemented.
