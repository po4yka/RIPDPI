## Context

Portfolio task `RST-1786264762917234` owns this change. Sixteen Arti and
netlink crates still depend on `paste`. The user selected a local adapter
on 2026-11-10 and authorized edits, commits, and a push directly on `main`.

## Goals / Non-Goals

- Goal: deliver `Replace unmaintained paste proc-macro dependency` with observable evidence.
- Goal: preserve the task's declared module, contract, and validation boundaries.
- Non-goal: broaden the change beyond the linked acceptance criteria.

## Decisions

- Treat the portfolio task as the source of priority, ownership, and lifecycle state.
- Treat this OpenSpec change as the normative behavior delta for the `rust-native` area.
- Keep implementation details in the affected modules and record exact verification evidence separately.
- Patch `paste` to `native/rust/vendor/paste`. Keep version 1.0.15 to satisfy
  downstream requirements; replace the implementation with explicit re-exports
  of `pastey::{paste, item, expr}`. No upstream `paste` source is retained.
- Use a `no_std` target library. Register it as a workspace member so CI runs
  the compatibility tests. Check the published `pastey` source and checksum
  before compiling it.
- Test nested expansion, case conversion, legacy exports, lifetimes, and
  environment fragments. Run workspace tests and clippy, the CI-pinned
  cargo-deny, source gates, and the Android debug build.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all mdtask steps and evidence categories to be resolved.
- Parallel work could collide in shared contracts. → Follow the serialized-file and worktree rules in `AGENTS.md`.
- The local package keeps the old name and version. Check Cargo metadata for
  its local source and `pastey` dependency; the disappearance of an advisory
  alone does not prove that the old implementation was replaced.

## Migration Plan

Work directly on `main` as explicitly requested. This task owns the Cargo
manifest, lockfile, adapter, advisory policy, and linked task artifacts. Run
the local gates before commit, then push and inspect the exact SHA's CI run.
Archive only through `taskctl` after all required evidence is available.
