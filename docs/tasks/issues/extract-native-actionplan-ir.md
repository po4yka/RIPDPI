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


## nekobox-subscription-and-profile
