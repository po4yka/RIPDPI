## Context

Portfolio task `RST-1786264762917569` owns this change. The 2026-06-10 architecture audit found two new actionable layering violations (both upward dependencies into the relay-transport layer L7):

## Goals / Non-Goals

- Goal: deliver `Introduce a WsTransport port to fix L6/L4 -> L7 dependencies on ripdpi-ws-tunnel` with observable evidence.
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
