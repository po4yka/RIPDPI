---
title: Define transparent vs owned-stack mode boundary
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: epic-direct-mode-diagnostic-state-machine
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-20
---

- [ ] #task Define transparent vs owned-stack mode boundary #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Summary

Make the two product modes sharply separate in code and docs. Transparent
mode (TUN + `VpnService.protect`) handles arbitrary third-party traffic;
owned-stack mode (browser + SDK) handles traffic we control. Invariants
differ per mode — enforce them at the boundary.

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] "Foundational constraint:
two product modes".

## Acceptance criteria

- [ ] Module boundary enforced: transparent-mode code cannot link to ECH
    / Cronet-owned code and vice versa.
- [ ] Shared types (DNS classification, `TransportPolicy`, `ArmStats`)
    live in a neutral module consumed by both.
- [ ] Architecture doc in `Docs/` explains the split, the invariants per
    mode, and how the diagnostic chooses between them.
- [ ] Invariant test: no transparent-mode arm can execute from an
    owned-stack code path by accident.

## Links

- [[Epic - Direct-mode diagnostic state machine]]
- [[Guard transparent mode against ClientHello byte mutation]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]


## direct-mode-transport-policy
