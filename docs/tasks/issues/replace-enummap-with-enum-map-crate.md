---
title: Replace hand-rolled EnumMap in ripdpi-collections with enum-map crate
type: task
status: backlog
area: rust-native
priority: low
owner: unassigned
parent: consolidate-rust-manual-implementations-with-vendored-deps
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Replace hand-rolled EnumMap in ripdpi-collections with enum-map crate #repo/RIPDPI #area/rust-native #status/backlog 🔽

## Summary

`ripdpi-collections/src/enum_map.rs` implements a `Vec<Option<V>>` keyed by enum discriminant cast to `usize`. The `enum-map = "2"` crate provides a proc-macro derive `#[derive(Enum)]` that generates the same layout with compile-time bounds checking, ergonomic `Index`/`IndexMut`, and iteration — no `Option<V>` wrapper needed.

## Implementation steps

1. Add `enum-map = "2"` to `[workspace.dependencies]`.
2. Annotate each enum that currently uses `EnumMap<K, V>` with `#[derive(enum_map::Enum)]`.
3. Replace `EnumMap<K, V>` usages with `enum_map::EnumMap<K, V>` (same name, different type — adjust `use` paths).
4. Delete `ripdpi-collections/src/enum_map.rs` and remove from `lib.rs`.
5. Update the public re-export in `ripdpi-collections` if `EnumMap` was exported.
6. `cargo nextest run -p ripdpi-collections` and any crates that used `EnumMap`.

## Acceptance criteria

- [ ] `enum-map` in `[workspace.dependencies]`.
- [ ] `enum_map.rs` deleted from `ripdpi-collections`.
- [ ] All call sites compile against `enum_map::EnumMap`.
- [ ] `cargo test -p ripdpi-collections` passes.
