---
title: Extract DirectPathLearningPort from PolicyPort
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

- [ ] #task Extract DirectPathLearningPort from PolicyPort #repo/RIPDPI #area/rust-native #status/backlog ⏫

## Objective

Separate the 8 direct-path learning methods from `PolicyPort` into a `DirectPathLearningPort` trait so callers that only record learning outcomes do not depend on the full route-selection surface.

## Context

`PolicyPort` (policy_port.rs:18–144) has 20 methods across route selection (8 methods), retry penalties (1 method), direct-path learning (8 `note_direct_path_*` + `emit_due_*` methods), autolearn/telemetry flush (2 methods), and persistence (1 method). Single implementor: `ServicesStateHandle`. Callers recording direct-path outcomes pull in the full routing interface.

Source: `native/rust/crates/ripdpi-runtime-policy/src/policy_port.rs:18-144`
Impl: `native/rust/crates/ripdpi-runtime-services/src/policy_port_impl.rs:17`

## Acceptance criteria

- [ ] `DirectPathLearningPort` trait defined in `ripdpi-runtime-policy` (or a new `ripdpi-runtime-direct-path` crate if the scope warrants it), containing all `note_direct_path_*` methods and `emit_due_direct_path_learning_timeouts`.
- [ ] `PolicyPort` retains route selection, retry penalties, autolearn, and persistence methods (≤12 methods).
- [ ] `ServicesStateHandle` implements both traits.
- [ ] Callers that only write learning observations updated to depend on `DirectPathLearningPort`.
- [ ] `cargo nextest run` green; no behavioral change.

## Definition of done

`PolicyPort` ≤12 methods; `DirectPathLearningPort` exists with its own impl; all callers compile.
