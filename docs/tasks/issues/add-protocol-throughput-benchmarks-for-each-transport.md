---
title: Add Criterion throughput benchmarks for each transport
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

- [ ] #task Add Criterion throughput benchmarks for each transport #repo/RIPDPI #area/testing #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-protocol-throughput-benchmarks-for-each-transport`
- **Verify:** `cargo bench -p ripdpi-bench -- --test`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-bench/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Wire one Criterion benchmark per transport (VLESS, xHTTP, MASQUE,
Hysteria 2, TUIC, ShadowTLS, WS tunnel) into `ripdpi-bench` so the
`regression-detector` agent can gate throughput regressions per
release.

## Context

`ripdpi-bench` exists in the workspace. The regression-detector agent
expects checked-in Criterion baselines for each transport. Today
there is no per-protocol throughput signal in CI, so a 30% bandwidth
regression in xHTTP could ship unnoticed.

## Acceptance criteria

- [ ] One Criterion benchmark per transport that drives a loopback
    pair through a representative payload size (e.g. 1 MiB).
- [ ] Baselines committed under
    `native/rust/crates/ripdpi-bench/baselines/`.
- [ ] `regression-detector` agent is wired into a nightly CI lane.

## Definition of done

- A deliberate 25% slowdown in any one transport fails the
  regression-detector lane.

## Links

- [[Epic - Control-plane hardening]]
