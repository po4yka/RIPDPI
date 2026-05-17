---
title: Add QUIC path-MTU discovery regression test
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

- [ ] #task Add QUIC path-MTU discovery regression test #repo/RIPDPI #area/testing #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `add-quic-path-mtu-discovery-regression-test`
- **Verify:** `cargo test -p ripdpi-hysteria2 -p ripdpi-tuic -p ripdpi-masque -- mtu`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-hysteria2/**`, `native/rust/crates/ripdpi-tuic/**`, `native/rust/crates/ripdpi-masque/**`, `docs/tasks/GOAL_LEDGER.md`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Path-MTU shifts (carrier handover, VPN nesting, jumbo-frame paths) break QUIC connections quietly. Add a regression test that simulates a mid-connection MTU drop and asserts the QUIC stack recovers.

## Context

Hysteria 2, TUIC, and MASQUE all run over Quinn. Quinn's PMTUD behaviour is configurable but easy to misconfigure. A small deliberate MTU drop in a loopback harness should be a recoverable event, not a connection kill.

## Acceptance criteria

- [ ] A shared `quic_mtu_test_util` (under a `dev-dependencies` crate or a `tests/common/`) injects an MTU drop on a loopback UDP socket.
- [ ] Each of Hysteria 2, TUIC, and MASQUE has one regression test asserting connection survival and payload integrity after the drop.
- [ ] The test runs in CI's standard test lane (not nightly).

## Definition of done

- A Quinn configuration regression that disables PMTUD fails the test.

## Links

- [[add-port-hopping-window-soak-test-for-hysteria2]]
