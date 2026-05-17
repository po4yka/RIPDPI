---
title: Add VLESS mux conformance tests against xray-core
type: task
status: backlog
area: testing
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-15
---

- [ ] #task Add VLESS mux conformance tests against xray-core #repo/RIPDPI #area/testing #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-vless-mux-conformance-tests-against-xray-core`
- **Verify:** `cargo test -p ripdpi-vless -- mux`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-vless/**`, `contract-fixtures/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

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

- [[tag-protocol-contract-fixtures-by-upstream-version]]
