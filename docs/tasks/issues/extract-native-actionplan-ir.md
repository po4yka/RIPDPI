---
title: Extract native ActionPlan IR
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

- [ ] #task Extract native ActionPlan IR #repo/RIPDPI #area/service #status/backlog 🔼

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `extract-native-actionplan-ir`
- **Verify:** `cargo nextest run --manifest-path native/rust/Cargo.toml -p ripdpi-desync-runtime`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-desync-runtime/**`, `native/rust/crates/ripdpi-desync/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Introduce a first-class internal `ActionPlan` IR in the Rust runtime so
planning, emission, and fallback decisions become independently testable
concerns.

## Audit citation

- Highest-ROI recommendation #3 in [[ripdpi-android-audit-2026-04-20]].

## Acceptance criteria

- [ ] `ActionPlan` type defined with enough fidelity to describe current
    desync / emit flows.
- [ ] Planner produces an `ActionPlan`; emitter consumes one; fallback
    classifier operates on it.
- [ ] Round-trip tests for plan → emission on representative scenarios.
- [ ] At least one existing use-site migrated to the IR as a pilot; others
    can follow incrementally.

## Notes

Decide IR shape in a spike before committing to a public surface. Keep the
IR internal to the Rust runtime initially — no JNI exposure required.

## Links

- [[Epic - Native hotspot decomposition]]
- [[Decompose desync.rs by responsibility]]
- [[ripdpi-android-audit-2026-04-20]]


## reference-implementation-subscription-and-profile
