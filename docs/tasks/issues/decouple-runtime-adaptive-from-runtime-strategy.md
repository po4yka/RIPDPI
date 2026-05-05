---
title: Decouple ripdpi-runtime-adaptive from ripdpi-runtime-strategy
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

- [ ] #task Decouple ripdpi-runtime-adaptive from ripdpi-runtime-strategy #repo/RIPDPI #area/rust-native #status/backlog ⏫

## Objective

Move `StrategyEvolutionResolver` (and any other concrete wiring using `ripdpi-runtime-strategy`) out of `ripdpi-runtime-adaptive` into `ripdpi-runtime-services`, leaving `ripdpi-runtime-adaptive` as a pure port/trait definition crate.

## Context

`ripdpi-runtime-adaptive` is a port crate defining the `AdaptivePort` trait and port implementations. Its `Cargo.toml` (line 17) directly depends on `ripdpi-runtime-strategy`, a sibling concrete impl crate. Port crates should not depend on concrete impl peers — that wiring belongs in `ripdpi-runtime-services`. The current coupling means adding or swapping strategy implementations requires touching the port crate.

Source: `native/rust/crates/ripdpi-runtime-adaptive/Cargo.toml:17`

## Acceptance criteria

- [ ] `ripdpi-runtime-strategy` removed from `ripdpi-runtime-adaptive/Cargo.toml`.
- [ ] `StrategyEvolutionResolver` (and any other type that required the dep) moved to `ripdpi-runtime-services` or a new composition helper.
- [ ] `ripdpi-runtime-adaptive` compiles with no dependency on `ripdpi-runtime-strategy`.
- [ ] `cargo deny check` passes with the updated dependency graph.
- [ ] All existing tests green.

## Definition of done

`ripdpi-runtime-adaptive/Cargo.toml` contains no reference to `ripdpi-runtime-strategy`; workspace builds clean.
