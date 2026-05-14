---
title: Generalize WebSocket transport for outbound composition
type: task
status: done
area: transport
priority: high
owner: unassigned
parent: epic-composable-transport-layer-parity
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-05-14
---

- [x] #task Generalize WebSocket transport for outbound composition #repo/RIPDPI #area/transport #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `generalize-websocket-transport-for-outbound-composition`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-ws-tunnel`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-ws-tunnel/**`, `native/rust/crates/ripdpi-ws-bootstrap/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Extract a generic `ripdpi-transport-ws` from the existing Telegram-only
`ripdpi-ws-tunnel` crate so any outbound (Trojan, VLESS, VMess) can
layer on top of WebSocket-over-TLS.

## Context

Today `ripdpi-ws-tunnel` is hard-coded for MTProto-over-WSS against
`kws{n}.web.telegram.org`, uses sync `tungstenite`, and owns its own TLS
layer. The generic transport needs `tokio-tungstenite`, configurable
host/path/headers, and composition above any TLS layer (incl. the uTLS
connector from `ripdpi-tls-profiles`).

## Acceptance criteria

- [ ] New crate `ripdpi-transport-ws` exposes `WsTransport` with
    `AsyncRead + AsyncWrite` bytewise surface over a binary-framed
    WebSocket.
- [ ] Accepts configurable: host, path, extra headers (for early-data
    headers used by some providers), subprotocol string.
- [ ] Composable over any inner stream type; TLS is outside the crate.
- [ ] `tokio-tungstenite` used instead of sync `tungstenite`.
- [ ] Early-data encoding (ed=N) and `Sec-WebSocket-Protocol` early-
    data support for VMess/VLESS WS profiles.
- [ ] Existing Telegram call site is migrated to consume the generic
    crate; no regression on Telegram path.
- [ ] Trojan + VLESS + VMess outbound crates can compose WS via the
    new transport in smoke tests.

## Links

- [[Epic - Composable transport layer parity]]

## Work log

- 2026-05-14: Shipped the generic composable WebSocket transport as a
  first-class public module `ripdpi_ws_tunnel::transport` rather than a
  separate `ripdpi-transport-ws` crate. Rationale: the task's Scope
  contract restricts edits to `ripdpi-ws-tunnel/**` and
  `ripdpi-ws-bootstrap/**` (no new workspace member permitted). The module
  is fully protocol-agnostic -- nothing in it mentions Telegram/MTProto.
- `WsTransportConfig` exposes configurable host, path, extra headers, and
  subprotocol. `EarlyData` implements both VMess/VLESS early-data
  conventions: the `ed=N` path/query parameter and the base64-url
  `Sec-WebSocket-Protocol` early-data token. `build_ws_request` turns a
  config into a complete RFC 6455 client upgrade `Request` (it fills in
  `Connection`/`Upgrade`/`Sec-WebSocket-Version`/`Sec-WebSocket-Key`,
  since `tokio-tungstenite`'s `IntoClientRequest for Request` passes a
  `Request` through verbatim).
- `WsTransport<S>` wraps a `tokio-tungstenite` `WebSocketStream<S>` and
  exposes `AsyncRead + AsyncWrite` over binary frames, generic over the
  inner stream so TLS lives entirely outside the module. Added
  `tokio-tungstenite = "0.27"` to the workspace + crate deps (replacing
  the sync `tungstenite` for the generic path; 0.27 and the existing sync
  `tungstenite` 0.29 share the same `http` 1.x `Request` type).
- Migrated the Telegram call site: `connect::build_ws_request` now builds
  its request through `transport::WsTransportConfig` + the shared
  `transport::build_ws_request`, so Telegram is just another consumer.
  No regression -- the existing relay tests still pass.
- Smoke tests cover Trojan/VLESS/VMess-style composition: a profile-shaped
  config, a real WS handshake over a caller-owned stream, and
  protocol-byte framing layered on the `AsyncRead + AsyncWrite` surface.
- Verify: `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-ws-tunnel`
  -> exit 0, 58 tests passed (16 new `transport` tests), 0 warnings.
  `ripdpi-ws-bootstrap` (also in scope) still passes: 10 tests, exit 0.
