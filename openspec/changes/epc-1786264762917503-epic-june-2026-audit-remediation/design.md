## Context

Portfolio task `EPC-1786264762917503` owns this change. Remediate the findings from the 2026-06-10 full-project audit (six parallel specialized passes: Rust API quality, unsafe code, async cancel-safety, JNI boundary, Kotlin/Android design, and architecture layering) across the ~112-crate native Rust workspace and the Android app. Close the one real shutdown bug, the one privacy-rule violation, and the cluster of medium-severity correctness and structural issues, while preserving the confirmed-healthy posture (no UB, no JNI signature mismatches, no…

## Goals / Non-Goals

- Goal: deliver `Epic - June 2026 full-project audit remediation` with observable evidence.
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
