---
title: Add VLESS mux conformance tests against xray-core
type: task
status: todo
area: testing
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-06-05
---

## Summary

`ripdpi-vless/src/mux.rs` (10 KB) implements VLESS-mux. Add golden-bytes conformance tests so xray-core mux frames produced by RIPDPI parse cleanly upstream (and vice versa).

## Context

VLESS-mux multiplexes multiple logical streams over a single VLESS connection. Subtle framing bugs cause silent stream interleaving errors that surface as random TLS handshake failures inside the tunneled traffic.

## Acceptance criteria

- [ ] At least eight golden frame payloads under `contract-fixtures/vless/<upstream-tag>/mux/`.
- [ ] `mux::tests` parses each golden and asserts encode-decode round-trip equality.
- [ ] One test exercises the multi-stream interleave path with at least three concurrent streams.

## Definition of done

- A deliberate framing-bit change in `mux.rs` fails a named golden test.

## Links

- `contract-fixtures/vless/README.md`

## Work log

- 2026-06-05: Fixture walker `upstream_yamux_fixtures_round_trip` exists in `ripdpi-relay-mux/src/wire_mux/yamux.rs:540`; only 1 golden fixture present (`contract-fixtures/vless/v1.260206.0/mux/yamux/syn-data-stream1-abc.bin`) vs required 8+; no multi-stream interleave test found; no sing-mux fixture walker or fixtures; all three acceptance criteria unmet.
- 2026-06-05: Re-verified: `find contract-fixtures/vless -name "*.bin"` yields exactly 1 file (criterion 1 unmet). The `upstream_yamux_fixtures_round_trip` test in `ripdpi-relay-mux/src/wire_mux/yamux.rs:539-566` performs decode+re-encode round-trip assertions on all `.bin` files in the yamux fixture dir, but only 1 file exists so the 8-fixture floor is not met (criterion 2 unmet). No multi-stream interleave test with 3+ concurrent streams found in `ripdpi-vless/src/mux.rs` or `ripdpi-relay-mux/` (criterion 3 unmet). Status remains `todo`.
