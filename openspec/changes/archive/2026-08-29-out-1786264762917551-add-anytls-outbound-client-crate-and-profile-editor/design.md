## Context

Portfolio task `OUT-1786264762917551` owns this change. AnyTLS is now a first-class relay kind with a Rust crate, relay-core backend, URI/subscription import support, and runtime config fields. Keep this task for the remaining UI and compatibility polish that is not yet present in the codebase

## Goals / Non-Goals

- Goal: deliver `Finish AnyTLS profile editor and compatibility gaps` with observable evidence.
- Goal: preserve the task's declared module, contract, and validation boundaries.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Treat the portfolio task as the source of priority, ownership, and lifecycle state.
- Treat this OpenSpec change as the normative behavior delta for the `outbound` area.
- Keep implementation details in the affected modules and record exact verification evidence separately.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all mdtask steps and evidence categories to be resolved.
- Parallel work could collide in shared contracts. → Follow the serialized-file and worktree rules in `AGENTS.md`.

## Migration Plan

Implement in an isolated worktree, run the named local and hosted gates, then archive only through `taskctl` after review.
