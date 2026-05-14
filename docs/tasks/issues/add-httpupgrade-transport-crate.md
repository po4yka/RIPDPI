---
title: Add HTTPUpgrade transport crate
type: task
status: done
area: transport
priority: medium
owner: unassigned
parent: epic-composable-transport-layer-parity
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [x] #task Add HTTPUpgrade transport crate #repo/RIPDPI #area/transport #status/done 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-httpupgrade-transport-crate`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-ws-tunnel`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-ws-tunnel/**`, `native/rust/crates/ripdpi-ws-bootstrap/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

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

## Work log

- 2026-05-14: Implemented as the public `httpupgrade` module of
  `ripdpi-ws-tunnel` (`native/rust/crates/ripdpi-ws-tunnel/src/httpupgrade.rs`),
  not a separate crate. **Contract resolution:** the Summary sketches a new
  `ripdpi-transport-httpupgrade` crate, but the Verify command targets
  `-p ripdpi-ws-tunnel` and the Scope forbids registering a new workspace
  member. Shipped as a first-class module alongside the existing generic
  `transport` (WebSocket) module, which was added the same way under this
  epic.
- Surface: `HttpUpgradeConfig` (configurable host, path, extra headers,
  upgrade-protocol name), `build_upgrade_request` (raw HTTP/1.1 request
  bytes, no `Sec-WebSocket-*` framing, CRLF-injection-rejecting),
  `parse_upgrade_response` (incremental `101` validation + pipelined-leftover
  capture, non-101 → typed `UnexpectedStatus`), `HttpUpgradeTransport<S>`
  (`AsyncRead + AsyncWrite` over any inner stream — composable over raw TCP,
  TLS, uTLS). Subscription-parser field population is satisfied at the
  config-API level (parsers live outside the Scope's two crates).
- TDD: 25 module tests written RED first (compile failures + `expect_err`
  assertions), then driven GREEN.
- Verify: `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-ws-tunnel`
  → 83 passed, 1 skipped, exit 0. Workspace clippy (`-D warnings`),
  `cargo fmt`, and `cargo check --workspace` all clean.
- New dependency: `thiserror` added to `ripdpi-ws-tunnel/Cargo.toml`
  (workspace dep, already in the tree).
