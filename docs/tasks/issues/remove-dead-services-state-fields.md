---
title: Remove dead ServicesState fields and cap RwLock field growth
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

- [ ] #task Remove dead ServicesState fields and cap RwLock field growth #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Objective

Remove the `#[allow(dead_code)] retry_stealth` field from `ServicesState` and prevent further unchecked growth of `Arc<RwLock<...>>` fields.

## Context

`ServicesState` (ripdpi-runtime-services/src/services_state.rs:26–50) grew from 4 to 6 `Arc<RwLock<...>>` fields since the April audit. The `retry_stealth` field is marked `#[allow(dead_code)]` — it carries allocation and locking overhead with no consumer. Unchecked field addition without removing dead fields is a trend that worsens lock contention over time.

Source: `native/rust/crates/ripdpi-runtime-services/src/services_state.rs:26-50`

## Acceptance criteria

- [ ] `retry_stealth` field removed from `ServicesState`; `#[allow(dead_code)]` suppression removed.
- [ ] Any initialization site for `retry_stealth` cleaned up.
- [ ] `cargo build --workspace` green with no dead-code warnings on `ServicesState`.
- [ ] A comment or doc note added to `ServicesState` listing the intended field inventory, so future additions are explicit decisions.

## Definition of done

`retry_stealth` absent from `ServicesState`; zero `#[allow(dead_code)]` suppressions on struct fields in the file; workspace builds clean.
