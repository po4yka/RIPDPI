## Context

Portfolio task `TRN-1786264762917677` owns this change. Plain WireGuard on the observed regional network path experiences periodic 20–30 second interruptions every ~30 seconds — middlebox/device fingerprinting can identify WireGuard via the deterministic 148-byte Initiation packet structure (4-byte type, 4-byte sender index, 32-byte ephemeral public key, 48-byte encrypted static key, 28-byte encrypted timestamp, 16-byte MAC1, 16-byte MAC2). AmneziaWG (AWG) randomizes this signature with junk/header/initialization parameters

## Goals / Non-Goals

- Goal: deliver `Wire AmneziaWG RTK South cohort (Jc=4) into Android client` with observable evidence.
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
