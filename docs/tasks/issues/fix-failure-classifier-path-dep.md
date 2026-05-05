---
title: Replace ripdpi-failure-classifier path dependency with workspace dependency
type: task
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: epic-srp-and-architecture-refactoring
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Replace ripdpi-failure-classifier path dependency with workspace dependency #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Objective

Change `ripdpi-packets = { path = "../ripdpi-packets" }` in `ripdpi-failure-classifier/Cargo.toml` to `ripdpi-packets = { workspace = true }` so workspace-wide version management applies consistently.

## Context

`ripdpi-failure-classifier/Cargo.toml:12` uses a raw path dependency for `ripdpi-packets` while every other crate in the workspace uses `{ workspace = true }`. Raw path deps bypass `[workspace.dependencies]` version pinning and are not updated by `cargo update` workspace-wide, risking version divergence.

Source: `native/rust/crates/ripdpi-failure-classifier/Cargo.toml:12`

## Acceptance criteria

- [ ] `ripdpi-packets` in `ripdpi-failure-classifier/Cargo.toml` changed to `{ workspace = true }`.
- [ ] `ripdpi-packets` present in `[workspace.dependencies]` in the root `Cargo.toml` (add if missing).
- [ ] `cargo build -p ripdpi-failure-classifier` and `cargo build --workspace` succeed.
- [ ] `Cargo.lock` updated consistently.

## Definition of done

No raw `path =` dependencies on sibling workspace crates remain in `ripdpi-failure-classifier/Cargo.toml`.
