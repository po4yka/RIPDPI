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
updated: 2026-05-31
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
