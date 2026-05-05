---
title: Restrict ServicesStateHandle inner Arc field to pub(crate)
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

- [ ] #task Restrict ServicesStateHandle inner Arc field to pub(crate) #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Objective

Change the tuple field of `ServicesStateHandle` from `pub` to `pub(crate)` so external crates cannot bypass the port-trait abstraction boundary to access internal `ServicesState` fields directly.

## Context

`ServicesStateHandle(pub Arc<ServicesState>)` (ripdpi-runtime-services/src/lib.rs:18) exposes the inner `Arc` as a public field. Any crate holding a handle can reach `.0.cache`, `.0.adaptive_tuning`, and all other fields of `ServicesState`, defeating the port-abstraction boundary the trait design enforces. The `pub` was likely added to satisfy the in-crate `Deref` impl, which does not require the field to be externally visible.

Source: `native/rust/crates/ripdpi-runtime-services/src/lib.rs:18`

## Acceptance criteria

- [ ] `ServicesStateHandle(pub(crate) Arc<ServicesState>)` — field visibility tightened.
- [ ] The `Deref` impl in the same crate still compiles.
- [ ] No external crate accesses `.0` directly; any that do are refactored to go through a trait method.
- [ ] `cargo build --workspace` green.

## Definition of done

No external crate accesses `ServicesStateHandle.0`; workspace builds clean.
