## Context

Portfolio task `EPC-1786264762917457` owns this change. > 2026-06-01 — scope reduced per ADR 0004. VMess, Trojan-Go, and Hysteria v1 are dropped from this epic and removed from the codebase — they were never-completed stubs that carried no traffic, and RIPDPI maintains support only for current/actual protocols. The remaining open backlog is SSH and Mieru only (not-yet-implemented compatibility work, explicitly not legacy). Their child tasks are deleted

## Goals / Non-Goals

- Goal: deliver `Epic - Extended outbound protocol support` with observable evidence.
- Goal: preserve the task's declared module, contract, and validation boundaries.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Treat the portfolio task as the source of priority, ownership, and lifecycle state.
- Treat this OpenSpec change as the normative behavior delta for the `epic` area.
- Keep implementation details in the affected modules and record exact verification evidence separately.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all mdtask steps and evidence categories to be resolved.
- Parallel work could collide in shared contracts. → Follow the serialized-file and worktree rules in `AGENTS.md`.

## Migration Plan

Implement in an isolated worktree, run the named local and hosted gates, then archive only through `taskctl` after review.
