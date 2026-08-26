## Context

Portfolio task `TRN-1786264762917886` owns this change. Race the simple flavor's seeded VLESS+Reality and Hysteria2+Salamander relay paths with an application-level probe before the VPN TUN is exposed, select the first confirmed-good transport, and retain the existing post-connection failover and UCB1 behavior

## Goals / Non-Goals

- Goal: deliver `Add a parallel active-probe race for initial transport selection` with observable evidence.
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
