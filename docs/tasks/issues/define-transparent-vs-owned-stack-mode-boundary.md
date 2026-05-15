---
title: Define transparent vs owned-stack mode boundary
type: task
status: done
area: diagnostics
priority: high
owner: unassigned
parent: epic-direct-mode-diagnostic-state-machine
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-15
---

- [x] #task Define transparent vs owned-stack mode boundary #repo/RIPDPI #area/diagnostics #status/done ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `define-transparent-vs-owned-stack-mode-boundary`
- **Verify:** `just test-module core:diagnostics`
- **Scope (only modify these + this file + the ledger):** `core/diagnostics/**`, `core/engine/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

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
