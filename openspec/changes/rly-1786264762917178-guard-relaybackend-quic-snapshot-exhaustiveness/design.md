## Context

Portfolio task `RLY-1786264762917178` owns this change. The 2026-06-10 Rust API audit noted RelayBackend reached 14 variants (was 12; Mieru and Ssh added). The dispatchpooledbackend! macro was updated correctly. Re-verified 2026-06-11 against native/rust/crates/ripdpi-relay-core/src/backend.rs: of the three manual match self blocks, quicmigrationsnapshot() (backend.rs:85-102) and openudpsession() (backend.rs:122-141) already enumerate all 14 variants with explicit |-joined arms and no catch-all , so adding a variant fails to compile (non-exhaustive…

## Goals / Non-Goals

- Goal: deliver `Guard RelayBackend manual match arms against silently-omitted QUIC variants` with observable evidence.
- Goal: preserve the task's declared module, contract, and validation boundaries.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Treat the portfolio task as the source of priority, ownership, and lifecycle state.
- Treat this OpenSpec change as the normative behavior delta for the `relay` area.
- Keep implementation details in the affected modules and record exact verification evidence separately.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all mdtask steps and evidence categories to be resolved.
- Parallel work could collide in shared contracts. → Follow the serialized-file and worktree rules in `AGENTS.md`.

## Migration Plan

Implement in an isolated worktree, run the named local and hosted gates, then archive only through `taskctl` after review.
