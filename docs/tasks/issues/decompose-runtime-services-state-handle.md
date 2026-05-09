---
title: Decompose runtime services state handle
type: task
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: epic-post-refactor-architecture-cleanup
blocks: []
blocked_by: []
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Decompose runtime services state handle #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Summary

Split `native/rust/crates/ripdpi-runtime-services/src/services_state.rs` so policy cache, adaptive tuning, retry pacing, strategy evolution, direct-path learning, telemetry, network identity, and warmup channel state are not hidden behind one broad handle.

## Context

`ServicesState` is adjacent to the proxy-runtime state cleanup and risks becoming the new native runtime service kernel. It constructs and stores several independent state machines and flushes multiple persistence surfaces from one type.

## Acceptance criteria

- [ ] Extract policy/cache, adaptive, retry pacing, strategy evolution, direct-path learning, telemetry, and warmup handles.
- [ ] Keep construction explicit and avoid recreating a single all-purpose service accessor.
- [ ] Preserve persistence flush/drop behavior.
- [ ] Keep proxy-runtime and adapter call sites compiling through narrower ports.
- [ ] Add focused tests or compile-time checks for service construction and teardown.

## Completion outcome

Closing this task means runtime services are exposed as focused service handles instead of one state object that stores every policy, adaptive, retry, strategy, direct-path, telemetry, network, and warmup state machine.

## Regression guardrails

- Do not replace `ServicesState` with another all-purpose `RuntimeServices` bag.
- Do not make proxy-runtime call sites depend on policy-engine internals through convenience accessors.
- Do not couple persistence flush for unrelated services in one opaque drop path without ownership documentation.
- Do not close the task until construction/teardown behavior is covered and proxy-runtime dependencies remain narrow.
- Do not close the task without focused unit tests or compile-time contract tests for each extracted runtime service handle.

## Links

- [[Epic - Post-refactor architecture cleanup]]
- Blocks or follows: [[Decompose proxy runtime state kernel]]
