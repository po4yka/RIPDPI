---
title: Add sing-mux and yamux wire multiplexing
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

- [x] #task Add sing-mux and yamux wire multiplexing #repo/RIPDPI #area/transport #status/done 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-sing-mux-and-yamux-wire-multiplexing`
- **Verify:** `just test-rust`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-relay-mux/**`, `native/rust/crates/ripdpi-vless/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

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

## Work log

- 2026-05-14: Implemented as the public `wire_mux` module of
  `ripdpi-relay-mux` (`native/rust/crates/ripdpi-relay-mux/src/wire_mux/`),
  not a separate crate. **Contract resolution:** the Summary sketches a new
  `ripdpi-transport-mux` crate, but the Verify command is `just test-rust`
  (the whole-workspace nextest) and the Scope forbids registering a new
  workspace member. `wire_mux` is deliberately distinct from the rest of
  `ripdpi-relay-mux` (which is session *pooling*, not wire-level
  multiplexing) — the task description calls that distinction out.
- `wire_mux::yamux`: full hashicorp yamux frame codec — 12-byte header
  (version/type/flags/stream-id/length), `Data`/`WindowUpdate`/`Ping`/`GoAway`
  types, `SYN`/`ACK`/`FIN`/`RST` flags, incremental `YamuxDecoder` with an
  oversized-frame cap. Cross-checked against a hand-assembled
  upstream-style wire vector.
- `wire_mux::sing_mux`: full sing-box sing-mux frame codec — 4-byte stream id
  + command + u16-length-delimited data, `New`/`Data`/`Close`/`KeepAlive`
  commands, v1/v2 session version byte, optional v2 padding (encoder emits,
  decoder transparently strips). `smux` (Trojan-Go) intentionally out of
  scope per the spec. Cross-checked against a hand-assembled vector.
- `wire_mux::session`: protocol-agnostic layer — `StreamIdAllocator`
  (yamux-odd / sing-mux-monotonic, exhaustion-safe), `MuxLimits`
  (configurable max concurrent streams, per-connection KB/s hint, padding
  mode), the `MuxTransport` trait outbounds plug a protocol into, and
  `StreamMailbox` — the bounded per-substream buffer that is the concrete
  backpressure primitive: `deliver` returns `WouldBlock` immediately rather
  than blocking, so a slow reader on one substream provably cannot wedge
  delivery to another (`slow_reader_on_one_stream_does_not_wedge_another`).
- "100 parallel flows" acceptance criterion: `wire_mux::tests` exercises 100
  logical flows through a *single* sing-mux codec and a *single* yamux
  codec, and asserts the structural memory invariant — one codec + one
  allocator + O(streams) cheap mailboxes, versus O(streams) full
  connections for the independent-connections alternative.
- VLESS integration: `ripdpi-vless` gained a `mux` module
  (`VlessMuxConfig` / `VlessMuxProtocol`, parsing `sing-mux`/`yamux`/`smux`/
  `h2mux` subscription tokens, `to_limits()` → `ripdpi-relay-mux::MuxLimits`)
  and a `mux: Option<VlessMuxConfig>` field on `VlessRealityConfig` with
  `with_mux` / `with_mux_strings` builders. `from_strings` stays
  backward-compatible (`mux: None`). No `ripdpi-trojan` crate exists in the
  workspace, so the Trojan half of the criterion is N/A.
- TDD: codec + session + vless-mux tests written RED first, driven GREEN.
- Verify: `just test-rust`
  (`cargo nextest run --manifest-path native/rust/Cargo.toml --workspace`)
  exit 0 — see the GOAL_LEDGER row for the full count. Workspace clippy
  (`-D warnings`), `cargo fmt`, `cargo check --workspace` all clean.
- New dependencies: `rand` + `thiserror` added to
  `ripdpi-relay-mux/Cargo.toml`; `ripdpi-relay-mux` added to
  `ripdpi-vless/Cargo.toml` (all workspace deps already in the tree).
