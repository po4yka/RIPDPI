---
title: Add cross-stack chain tests (VLESS over xHTTP over Reality)
type: task
status: doing
area: testing
priority: low
owner: unassigned
parent: epic-protocol-conformance-tests
status_detail: partial — xHTTP single-stream done; mux test blocked on unimplemented VLESS wire-mux
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-06-10
---

## Summary

Real deployments stack protocols (VLESS-over-xHTTP-over-Reality, or VLESS-over-Reality with mux). Add loopback tests that exercise representative combinatorics so per-crate changes do not break stacked behavior.

## Context

Per-crate tests do not catch interaction bugs between transport layers: an xHTTP framing change that's correct in isolation can break the VLESS handshake that runs over it.

## Acceptance criteria

- [x] VLESS-over-xHTTP-over-Reality, single stream — `cross_stack_vless_over_xhttp_over_reality_single_stream` in `ripdpi-relay-core` drives the real xHTTP/Reality backend against `XhttpRealityLoopback` (Reality TLS + HTTP/2 stream-up + VLESS-in-body), two round-trips asserting bidirectional integrity, in the standard CI lane.
- [ ] VLESS-over-Reality with mux, two concurrent streams — **blocked** on the VLESS wire-mux datapath. `VlessMuxConfig` (sing-mux/yamux) is config/parse-only; `VlessRealityClient::connect`/`connect_over` never multiplex. This criterion **absorbs the former `add-vless-mux-conformance-tests-against-xray-core` task (merged 2026-06-10)** and additionally requires:
  - [ ] At least eight golden mux frame payloads under `contract-fixtures/vless/<upstream-tag>/mux/` (currently 1), each round-tripped encode/decode by `mux::tests`.
  - [ ] A multi-stream interleave test exercising at least three concurrent streams.
  - [ ] A deliberate framing-bit change in `ripdpi-vless/src/mux.rs` fails a named golden test.
  Until VLESS wire-mux framing lands in the datapath, none of these can be exercised.
- [x] Each landed test asserts payload integrity in both directions.
- [x] Landed test runs in the standard CI lane.

## Definition of done

- A correctness regression in any one layer breaks the cross-stack test even when per-crate tests pass.

## Links

- [[audit-vless-chained-connect-over-relay-end-to-end-tests]]
- `add-vless-mux-conformance-tests-against-xray-core` — merged into criterion 2 of this task (2026-06-10); file deleted.

## Work log

- 2026-06-05: no cross-stack tests exist; ripdpi-vless/tests/ has only manuallydrop_canary.rs and ripdpi-xhttp/src/tests.rs has per-crate unit tests only; all three acceptance criteria unmet, work not started
- 2026-06-05: added `XhttpRealityLoopback` to `local-network-fixture` (boring Reality `SslAcceptor` + hyper HTTP/2 server speaking the xray-core stream-up wire shape: GET `/<path>/<sid>` download body + POST upload body correlated by path, VLESS handshake carried in the H2 bodies, proxy to an embedded echo). Added `cross_stack_vless_over_xhttp_over_reality_single_stream` driving the real `vless_reality` xHTTP backend end to end — closes the single-stream criterion. The mux criterion remains blocked: VLESS wire-mux is not implemented in the datapath (only `VlessMuxConfig` parsing exists), so it needs the yamux/sing-mux feature landed first (tracked alongside [[add-vless-mux-conformance-tests-against-xray-core]]).
- 2026-06-05: source-verified all criteria. `cross_stack_vless_over_xhttp_over_reality_single_stream` confirmed at `ripdpi-relay-core/src/tests.rs:596`, uses `XhttpRealityLoopback` from `local-network-fixture/src/xhttp.rs`, asserts bidirectional payload integrity (two `assert_eq!` round-trips); runs via `cargo nextest run --workspace` in `scripts/ci/run-rust-workspace-tests.sh`. Mux criterion confirmed blocked: `VlessMuxConfig` is referenced only in `ripdpi-vless/src/{mux,config,lib}.rs` — no relay backend or datapath wiring found. Status upgraded from `backlog` to `doing` (criteria 1/3/4 [x], criterion 2 blocked).
