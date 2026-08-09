## Context

Portfolio task `TRN-1786264762917184` owns this change. The per-exit-IP concurrent-TLS cap (ExitIpSessionLimiter, ripdpi-proxy-runtime/src/exitipcap.rs) was wired into ripdpi-proxy-runtime's outbound connect path as an admission gate with route-preference on cap (skip an at-cap exit-IP candidate for an alternate; advisory fall-through when all are capped). That closed the originally-filed task

## Goals / Non-Goals

- Goal: deliver `Per-exit-IP TLS cap with true mux-preference in relay-core backend` with observable evidence.
- Goal: preserve the task's declared module, contract, and validation boundaries.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Treat the portfolio task as the source of priority, ownership, and lifecycle state.
- Treat this OpenSpec change as the normative behavior delta for the `transport` area.
- Keep implementation details in the affected modules and record exact verification evidence separately.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all mdtask steps and evidence categories to be resolved.
- Parallel work could collide in shared contracts. → Follow the serialized-file and worktree rules in `AGENTS.md`.

## Migration Plan

Implement in an isolated worktree, run the named local and hosted gates, then archive only through `taskctl` after review.
