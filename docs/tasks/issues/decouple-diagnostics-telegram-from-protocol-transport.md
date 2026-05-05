---
title: Decouple ripdpi-diagnostics-telegram from Protocol-layer ws-bootstrap and ws-tunnel
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: epic-srp-and-architecture-refactoring
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Decouple ripdpi-diagnostics-telegram from Protocol-layer ws-bootstrap and ws-tunnel #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Objective

Implement a thin WebSocket-over-TLS adapter inside `ripdpi-diagnostics-transport` (or a new `ripdpi-diagnostics-ws` crate) so `ripdpi-diagnostics-telegram` no longer imports full proxy Protocol-layer transport crates.

## Context

`ripdpi-diagnostics-telegram/Cargo.toml:15-16` depends directly on `ripdpi-ws-bootstrap` and `ripdpi-ws-tunnel`, which are Protocol-layer proxy transport crates. The diagnostics subsystem should be decoupled from the proxy transport stack — protocol changes can otherwise break diagnostics builds and vice versa. The diagnostics boundary should use only `ripdpi-diagnostics-transport` for network I/O.

Source: `native/rust/crates/ripdpi-diagnostics-telegram/Cargo.toml:15-16`

## Acceptance criteria

- [ ] A thin `WsOverTlsConnector` (or equivalent) added to `ripdpi-diagnostics-transport` that wraps a raw TLS socket for WebSocket framing without importing `ripdpi-ws-bootstrap` or `ripdpi-ws-tunnel`.
- [ ] `ripdpi-diagnostics-telegram` uses the new adapter instead of the Protocol-layer crates.
- [ ] `ripdpi-ws-bootstrap` and `ripdpi-ws-tunnel` removed from `ripdpi-diagnostics-telegram/Cargo.toml`.
- [ ] Telegram probe tests pass; no behavioral change.

## Definition of done

`ripdpi-diagnostics-telegram/Cargo.toml` contains no `ripdpi-ws-bootstrap` or `ripdpi-ws-tunnel` deps; probe tests green.
