---
title: Remove wildcard re-export of ripdpi-runtime-policy from ripdpi-runtime-adaptive
type: task
status: backlog
area: rust-native
priority: high
owner: unassigned
parent: epic-srp-and-architecture-refactoring
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Remove wildcard re-export of ripdpi-runtime-policy from ripdpi-runtime-adaptive #repo/RIPDPI #area/rust-native #status/backlog ⏫

## Objective

Remove the `pub mod runtime_policy { pub use ripdpi_runtime_policy::runtime_policy::*; }` block from `ripdpi-runtime-adaptive`, so callers must take an explicit dependency on `ripdpi-runtime-policy` to access its types.

## Context

`ripdpi-runtime-adaptive/src/lib.rs:11-13` re-publishes the entire `runtime_policy` namespace from `ripdpi-runtime-policy` under `ripdpi_runtime_adaptive::runtime_policy::*`. This erases the crate boundary between two distinct port crates, defeats dependency graph auditing, and silently expands `ripdpi-runtime-adaptive`'s public surface. It also creates a near-cycle dependency relationship that will block migrating `PolicyPort` to `ripdpi-runtime-api` without a coordinated two-crate change.

Source: `native/rust/crates/ripdpi-runtime-adaptive/src/lib.rs:11-13`

## Acceptance criteria

- [ ] `pub mod runtime_policy { pub use ripdpi_runtime_policy::runtime_policy::*; }` block removed.
- [ ] Any crate that was consuming `ripdpi_runtime_adaptive::runtime_policy::*` updated to import directly from `ripdpi_runtime_policy::runtime_policy`.
- [ ] Those callers add `ripdpi-runtime-policy = { workspace = true }` to their own `Cargo.toml` if not already present.
- [ ] `cargo build --workspace` and `cargo doc --workspace` pass.

## Definition of done

No `pub use ripdpi_runtime_policy` in `ripdpi-runtime-adaptive`; workspace builds and docs pass.
