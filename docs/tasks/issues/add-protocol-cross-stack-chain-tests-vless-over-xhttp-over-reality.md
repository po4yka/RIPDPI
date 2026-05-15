---
title: Add cross-stack chain tests (VLESS over xHTTP over Reality)
type: task
status: backlog
area: testing
priority: low
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-15
---

- [ ] #task Add cross-stack chain tests (VLESS over xHTTP over Reality) #repo/RIPDPI #area/testing #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-protocol-cross-stack-chain-tests-vless-over-xhttp-over-reality`
- **Verify:** `cargo test -p ripdpi-xhttp -p ripdpi-vless -- chain_xhttp_over_reality`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-xhttp/**`, `native/rust/crates/ripdpi-vless/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Real deployments stack protocols (VLESS-over-xHTTP-over-Reality,
or VLESS-over-Reality with mux). Add loopback tests that exercise
representative combinatorics so per-crate changes do not break
stacked behavior.

## Context

Per-crate tests do not catch interaction bugs between transport
layers: an xHTTP framing change that's correct in isolation can
break the VLESS handshake that runs over it.

## Acceptance criteria

- [ ] At least two cross-stack tests:
    - VLESS-over-Reality with mux, two concurrent streams.
    - VLESS-over-xHTTP-over-Reality, single stream.
- [ ] Each test asserts payload integrity in both directions.
- [ ] Tests run in the standard CI lane.

## Definition of done

- A correctness regression in any one layer breaks the cross-stack
  test even when per-crate tests pass.

## Links

- [[audit-vless-chained-connect-over-relay-end-to-end-tests]]
- [[add-vless-mux-conformance-tests-against-xray-core]]
