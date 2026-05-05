---
title: Document or remove ripdpi-runtime-learning orphan wiring crate
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

- [ ] #task Document or remove ripdpi-runtime-learning orphan wiring crate #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Objective

Either formally document `ripdpi-runtime-learning`'s role in the architecture and integrate it correctly, or remove it if it has no active consumers.

## Context

`ripdpi-runtime-learning` depends on all four Runtime-ports crates (`runtime-adaptive`, `runtime-dns-cache`, `runtime-policy`, `runtime-strategy`) without going through the declared sole wiring crate `ripdpi-runtime-services`. It has no visible direct consumers in the workspace — no crate in the dependency graph lists it as a dep. This suggests it may be an orphaned experiment or a crate whose role was absorbed into `ripdpi-runtime-services`.

Verify: `cargo tree --workspace -i ripdpi-runtime-learning`

## Acceptance criteria

- [ ] Run `cargo tree --workspace -i ripdpi-runtime-learning` to confirm consumer count.
- [ ] If zero consumers: remove the crate from the workspace `Cargo.toml` members list and delete the directory.
- [ ] If active consumers found: document its layer assignment in its `Cargo.toml` and `README.md`; define whether it is a sub-module of `ripdpi-runtime-services` or a distinct peer.
- [ ] `cargo build --workspace` green after decision.

## Definition of done

`ripdpi-runtime-learning` is either removed from the workspace or has a documented, justified layer assignment.
