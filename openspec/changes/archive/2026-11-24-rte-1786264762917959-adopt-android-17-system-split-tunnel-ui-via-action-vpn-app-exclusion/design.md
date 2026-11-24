## Context

Portfolio task `RTE-1786264762917959` owns this change. Android 17 added a system-owned split-tunnel UI: VPN apps fire ACTIONVPNAPPEXCLUSIONSETTINGS and the OS persists user exclusions across reconnects. Wire this from RIPDPI settings so the per-app exclusion state lives in the OS instead of in-app, reducing the risk of exclusion loss on reconnect

## Goals / Non-Goals

- Goal: deliver `Adopt Android 17 system split-tunnel UI via ACTION_VPN_APP_EXCLUSION_SETTINGS` with observable evidence.
- Goal: preserve the task's declared module, contract, and validation boundaries.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Treat the portfolio task as the source of priority, ownership, and lifecycle state.
- Treat this OpenSpec change as the normative behavior delta for the `routing` area.
- Keep implementation details in the affected modules and record exact verification evidence separately.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all mdtask steps and evidence categories to be resolved.
- Parallel work could collide in shared contracts. → Follow the serialized-file and worktree rules in `AGENTS.md`.

## Migration Plan

Implement in an isolated worktree, run the named local and hosted gates, then archive only through `taskctl` after review.
