---
title: Add port-hopping window soak test for Hysteria 2
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

- [ ] #task Add port-hopping window soak test for Hysteria 2 #repo/RIPDPI #area/testing #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-port-hopping-window-soak-test-for-hysteria2`
- **Verify:** `cargo test -p ripdpi-hysteria2 --release -- port_hopping`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-hysteria2/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

`ripdpi-hysteria2/src/port_hopping.rs` is 14 KB of stateful logic
that rebinds the local UDP socket on a recurring schedule.
Add a soak test that runs through many hop windows while injecting
path-MTU shifts and brief loss spikes.

## Context

Port hopping is a transport-evasion feature whose failure modes are
hard to surface in unit tests: a stuck endpoint, a leaked socket,
or an off-by-one in the hop schedule may only appear over minutes
of operation.

## Acceptance criteria

- [ ] A `port_hopping_soak` test (gated behind `#[ignore]` or a
    feature flag for CI cost control) runs at least 30 hop cycles
    against a loopback server.
- [ ] The test asserts: no leaked sockets, monotonic hop indices,
    and bidirectional bytes delivered every window.
- [ ] `HopIntervalTelemetry` counters match the asserted hop count.
- [ ] A nightly CI lane runs the soak; PR CI does not.

## Definition of done

- A regression that breaks hop scheduling after window N>10 is
  caught by the nightly soak.

## Links

- [[add-protocol-throughput-benchmarks-for-each-transport]]
- [[add-quic-path-mtu-discovery-regression-test]]
