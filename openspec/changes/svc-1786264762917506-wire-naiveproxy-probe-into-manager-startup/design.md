## Context

Portfolio task `SVC-1786264762917506` owns this change. The helper-side --probe line and Kotlin parser now exist. Finish the Android startup integration by invoking --probe before launch, rejecting unsupported schema versions, and documenting the enforced policy

## Goals / Non-Goals

- Goal: deliver `Wire NaiveProxy helper probe into manager startup` with observable evidence.
- Goal: preserve the task's declared module, contract, and validation boundaries.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Treat the portfolio task as the source of priority, ownership, and lifecycle state.
- Treat this OpenSpec change as the normative behavior delta for the `service` area.
- Keep implementation details in the affected modules and record exact verification evidence separately.
- Run a bounded `--probe` subprocess after extracting the bundled helper and before delegating that exact extracted file to the existing main-process launch path. Repeat the preflight for every watchdog restart.
- Support probe schema `1` only. Do not provide schema-0 fallback or a cutoff flag because extraction overwrites the helper from the current APK on every start; missing probe support therefore indicates packaging incompatibility, not a valid older installed runtime.
- Surface refusal as a typed relay-configuration startup rejection and record `relay_compatibility` in relay telemetry. Keep the existing `--version`, `RIPDPI-READY`, and `RIPDPI-ERROR` processing unchanged after successful preflight.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all mdtask steps and evidence categories to be resolved.
- Parallel work could collide in shared contracts. → Follow the serialized-file and worktree rules in `AGENTS.md`.

## Migration Plan

Implement in an isolated worktree, run the named local and hosted gates, then archive only through `taskctl` after review.
