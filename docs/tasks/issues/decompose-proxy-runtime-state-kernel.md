---
title: Decompose proxy runtime state kernel
type: task
status: backlog
area: rust-native
priority: high
owner: unassigned
parent: epic-post-refactor-architecture-cleanup
blocks: []
blocked_by: []
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Decompose proxy runtime state kernel #repo/RIPDPI #area/rust-native #status/backlog ⏫

## Summary

Split `native/rust/crates/ripdpi-proxy-runtime/src/runtime/state.rs` into focused runtime state/services so one file no longer owns handshake, routing, retry, desync, UDP, relay, telemetry, direct-path, warmup, and control operations.

## Context

`RuntimeState` still stores and exposes nearly every runtime concern. Methods span protocol parsing, route selection, retry pacing, first-response policy, failure classification, adaptive feedback, desync execution, encrypted DNS/WS resolution, telemetry emission, direct-path learning, and io_uring access.

## Acceptance criteria

- [ ] Extract handshake/session state accessors into a focused module.
- [ ] Extract routing/retry/failure-feedback accessors into a focused module.
- [ ] Extract desync planning/execution accessors into a focused module.
- [ ] Extract UDP/relay/first-response helpers into focused modules.
- [ ] Extract telemetry/direct-path/warmup/control helpers into focused modules.
- [ ] Keep a small `RuntimeState` facade for shared handles and construction only.
- [ ] Existing proxy runtime tests and packet/network smoke tests remain green.

## Completion outcome

Closing this task means `RuntimeState` is a construction and handle container, while runtime behavior lives behind focused accessors/services for handshake, routing, retry, desync, UDP, relay, telemetry, direct-path, warmup, and control concerns.

## Regression guardrails

- Do not add new behavior-heavy methods back to `RuntimeState`.
- Do not introduce a replacement `Context`, `Services`, or `Kernel` type that centralizes the same concerns under a new name.
- Do not let routing/failure policy changes touch handshake parsing, UDP forwarding, telemetry export, or desync execution modules.
- Do not close the task until proxy-runtime tests and architecture checks show the state kernel is no longer a P2/P3 hotspot.
- Do not close the task without focused unit tests for each extracted runtime state/service slice, or a written explanation of why a slice is compile-time/static-analysis only.

## Links

- [[Epic - Post-refactor architecture cleanup]]
- Blocks or follows: [[Split proxy runtime adapter model by operation family]]
