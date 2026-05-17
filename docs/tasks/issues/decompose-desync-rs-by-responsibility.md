---
title: Decompose desync.rs by responsibility
type: task
status: backlog
area: service
priority: medium
owner: unassigned
parent: epic-native-hotspot-decomposition
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-14
---

- [ ] #task Decompose desync.rs by responsibility #repo/RIPDPI #area/service #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `decompose-desync-rs-by-responsibility`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-desync`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-runtime/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

`desync.rs` mixes planning, fallback choice, fake-packet construction, TTL-sensitive send logic, and plan execution in 1538 LOC. Split by responsibility.

## Audit citation

- `native/rust/crates/ripdpi-runtime/src/runtime/desync.rs` — 1538 LOC, function-dense in practice.

## Acceptance criteria

- [ ] `desync.rs` split into: `planner`, `emitters`, `fallback` (classifier), `fake_packet` (builders).
- [ ] Each module has its own unit tests.
- [ ] No behavior change — existing integration/fuzz tests stay green.
- [ ] `file-loc-baseline.json` updated to reflect the split.

## Notes

Coordinate with [[Extract native ActionPlan IR]] — the planner module is the natural home for the IR.

## Links

- [[Epic - Native hotspot decomposition]]
- [[Extract native ActionPlan IR]]
- [[ripdpi-android-audit-2026-04-20]]
