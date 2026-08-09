## Context

Portfolio task `AND-1786264762917810` owns this change. The 2026-06-10 Kotlin audit found Hilt has grown to 134 SingletonComponent modules (up from 71+) with no custom VPN-session scope. Several service-layer singletons logically belong to a VPN-session lifetime — ServiceStateStore, RootHelperManager, VpnAppExclusionPolicy, VpnDhtMitigationPolicy, NetworkFingerprintProvider — yet are @Singleton, so state accumulated in one session persists into the next unless explicitly cleared (e.g., a stale ServiceStateStore emitting previous-session telemetry to…

## Goals / Non-Goals

- Goal: deliver `Introduce a VPN-session Hilt scope to reset per-session service state` with observable evidence.
- Goal: preserve the task's declared module, contract, and validation boundaries.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Treat the portfolio task as the source of priority, ownership, and lifecycle state.
- Treat this OpenSpec change as the normative behavior delta for the `android` area.
- Keep implementation details in the affected modules and record exact verification evidence separately.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all mdtask steps and evidence categories to be resolved.
- Parallel work could collide in shared contracts. → Follow the serialized-file and worktree rules in `AGENTS.md`.

## Migration Plan

Implement in an isolated worktree, run the named local and hosted gates, then archive only through `taskctl` after review.
