## Context

Portfolio task `RST-1786264762917192` owns this change. The 2026-06-10 Rust API audit flagged an Interface-Segregation violation. ripdpi-runtime-decision-ports/src/policy.rs:138 — PolicyPort now has 12 methods (threshold 8): selectinitial, notesuccess, advanceroute, noteblocksignal, supportstrigger, selectnext, storeroute, clearconnectioncache, buildretrypenalties, autolearnstate, drainautolearnevents, flushhoststore. Callers that only select routes are forced to depend on (and mock, in tests) the full learning surface

## Goals / Non-Goals

- Goal: deliver `Split the 12-method PolicyPort trait into selection and learning sub-traits` with observable evidence.
- Goal: preserve the task's declared module, contract, and validation boundaries.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Treat the portfolio task as the source of priority, ownership, and lifecycle state.
- Treat this OpenSpec change as the normative behavior delta for the `rust-native` area.
- Keep implementation details in the affected modules and record exact verification evidence separately.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all mdtask steps and evidence categories to be resolved.
- Parallel work could collide in shared contracts. → Follow the serialized-file and worktree rules in `AGENTS.md`.

## Migration Plan

Implement in an isolated worktree, run the named local and hosted gates, then archive only through `taskctl` after review.
