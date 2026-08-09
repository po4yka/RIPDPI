## Context

Portfolio task `EPC-1786264762917282` owns this change. Hold the speculative network-behavior R&D — traffic-shape obfuscation, alternative bootstrap channels, and empirical network-signature measurement — in one place so it is visibly research, not committed delivery. These tasks share the property that they need field measurement or a design spike before they can become implementation tasks, and several are gated on external RU-ISP vantage access

## Goals / Non-Goals

- Goal: deliver `Epic - Transport obfuscation and network-signature research` with observable evidence.
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
