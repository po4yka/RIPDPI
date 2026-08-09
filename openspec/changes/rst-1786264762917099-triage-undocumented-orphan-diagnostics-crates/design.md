## Context

Portfolio task `RST-1786264762917099` owns this change. The 2026-06-10 architecture audit flagged diagnostics prune candidates. Re-verified 2026-06-11 against docs/architecture/NATIVERUST.md and the workspace Cargo.tomls — the earlier "undocumented orphan" framing was inaccurate and is corrected here:

## Goals / Non-Goals

- Goal: deliver `Triage undocumented orphan crates and document NATIVE_RUST.md prune candidates` with observable evidence.
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
