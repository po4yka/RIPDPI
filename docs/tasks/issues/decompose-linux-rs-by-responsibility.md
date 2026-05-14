---
title: Decompose linux.rs by responsibility
type: task
status: backlog
area: service
priority: medium
owner: unassigned
parent: epic-native-hotspot-decomposition
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-20
---

- [ ] #task Decompose linux.rs by responsibility #repo/RIPDPI #area/service #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `decompose-linux-rs-by-responsibility`
- **Verify:** `just test-rust`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-runtime/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

`linux.rs` (1557 LOC) mixes socket options, protect logic, raw sends, TCP
repair, TTL capture, and low-level packet mutation. Split by responsibility.

## Audit citation

- `native/rust/crates/ripdpi-runtime/src/platform/linux.rs` — 1557 LOC.

## Acceptance criteria

- [ ] Split into: `sockopts`, `protect`, `raw_send`, `tcp_repair`.
- [ ] Each module has scoped unit tests where feasible.
- [ ] No behavior change — existing tests green.
- [ ] `file-loc-baseline.json` updated.

## Links

- [[Epic - Native hotspot decomposition]]
- [[ripdpi-android-audit-2026-04-20]]
