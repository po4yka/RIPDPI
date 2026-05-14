---
title: Generalize WebSocket transport for outbound composition
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

- [ ] #task Generalize WebSocket transport for outbound composition #repo/RIPDPI #area/transport #status/backlog ⏫

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
