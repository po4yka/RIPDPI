## Context

Portfolio task `TRN-1786264762917775` owns this change. The AmneziaWgProfileScreen / AwgProfileForm editor lets a user configure a full AmneziaWG peer (endpoint, keys, MTU, DNS, and the Jc/Jmin/Jmax/S1-S2/H1-H4/ I1-I5 obfuscation knobs) — but the app could not run it. The editor was preview-only: no Save/Connect, no persistence, no engine path. This is the same "UI-complete, core-stub" gap as SSH (G1). Distinct from WARP, which only drives Cloudflare's WireGuard endpoints

## Goals / Non-Goals

- Goal: deliver `Make the AmneziaWG profile UI establish a real tunnel (standalone AWG transport)` with observable evidence.
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
