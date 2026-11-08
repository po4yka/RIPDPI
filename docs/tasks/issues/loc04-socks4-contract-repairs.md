---
id: PRX-1794116241356560
title: Repair SOCKS4 and SOCKS4a protocol contracts
kind: bug
status: review
area: proxy
priority: high
owner: LOC-04 repair team
parent: null
blocked_by: []
spec_mode: required
openspec_change: loc04-socks4-contract-repairs
created: 2026-11-08
updated: 2026-11-08
---

## Goal

Repair all six confirmed LOC-04 findings and retain one commit per finding.

## Acceptance criteria

- Regression tests cover F1 through F6 and both SOCKS4 address modes.
- Targeted tests, affected Rust crate checks, offline CI source gates, and an independent review pass.
- Integrate the reviewed commits into main and push origin/main as requested.

## Parallel ownership

- Root: planning, task state, integration, F4 handshake DNS cancellation and deadline, combined validation, and push.
- Connect worker: F1; runtime/handshake/connect_relay, handshake/ws_tunnel.rs, runtime/ws.rs, state/ws.rs, ripdpi-ws-transport-port/src/lib.rs, ripdpi-ws-tunnel/src/lib.rs, and associated tests. After F2, this writer also owns the shared first-payload reader for exhausted-upstream handling.
- WS bootstrap worker: F1 bootstrap cancellation; ripdpi-ws-tunnel/src/connect.rs and its connect submodules/tests only. Connect worker alone owns the public port and runtime adapters.
- Relay worker: F2; runtime/relay subtree, runtime/relay.rs, and dedicated TCP regression tests only.
- Parser worker: F3, F5, F6; ripdpi-session/src/socks4.rs, protocol_io/readers.rs, the SOCKS4 reader error branch in handshake.rs, and dedicated tests. One commit per finding.
- All workers use separate worktrees. Root resolves any handshake.rs overlap after commit transfer.
- Root alone owns task files, OpenSpec artifacts, board generation, API snapshots, and integration metadata.
- Cargo.lock, version catalogs, schemas, locale sets, baselines, and golden fixtures are not assigned for edits.
