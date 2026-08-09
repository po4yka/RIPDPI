## Context

Portfolio task `SVC-1786264762917506` owns this change. The helper-side --probe line and Kotlin parser now exist. Finish the Android startup integration by invoking --probe before launch, rejecting unsupported schema versions, and documenting the enforced policy

## Goals / Non-Goals

- Goal: deliver `Wire NaiveProxy helper probe into manager startup` with observable evidence.
- Goal: preserve the task's declared module, contract, and validation boundaries.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Treat the portfolio task as the source of priority, ownership, and lifecycle state.
- Treat this OpenSpec change as the normative behavior delta for the `service` area.
- Keep implementation details in the affected modules and record exact verification evidence separately.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all mdtask steps and evidence categories to be resolved.
- Parallel work could collide in shared contracts. → Follow the serialized-file and worktree rules in `AGENTS.md`.

## Migration Plan

Implement in an isolated worktree, run the named local and hosted gates, then archive only through `taskctl` after review.
