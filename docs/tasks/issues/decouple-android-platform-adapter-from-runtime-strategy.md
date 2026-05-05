---
title: Remove ripdpi-runtime-strategy direct dep from ripdpi-android-platform-adapter
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

- [ ] #task Remove ripdpi-runtime-strategy direct dep from ripdpi-android-platform-adapter #repo/RIPDPI #area/rust-native #status/backlog ⏫

## Objective

Route strategy configuration through `ripdpi-runtime-platform` (which the adapter already depends on) or through an explicit config type, eliminating the JNI adapter's direct dependency on the concrete `ripdpi-runtime-strategy` crate.

## Context

`ripdpi-android-platform-adapter/Cargo.toml:15` directly depends on `ripdpi-runtime-strategy`. A JNI/Platform adapter should interact with strategy behavior only through an injected port trait or config type. The direct dep couples platform-specific code to a concrete runtime algorithm crate, meaning strategy implementation swaps require touching the JNI layer.

Source: `native/rust/crates/ripdpi-android-platform-adapter/Cargo.toml:15`

## Acceptance criteria

- [ ] `ripdpi-runtime-strategy` removed from `ripdpi-android-platform-adapter/Cargo.toml`.
- [ ] The strategy configuration surface exposed through `ripdpi-runtime-platform` or a dedicated config type in `ripdpi-config`.
- [ ] `ripdpi-android-platform-adapter` compiles with no direct reference to `ripdpi-runtime-strategy` types.
- [ ] Existing JNI integration tests pass.

## Definition of done

`ripdpi-android-platform-adapter/Cargo.toml` contains no `ripdpi-runtime-strategy` dep; JNI tests green.
