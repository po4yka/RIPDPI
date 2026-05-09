---
title: Split relay core runtime composition
type: task
status: backlog
area: relay
priority: medium
owner: unassigned
parent: epic-post-refactor-architecture-cleanup
blocks: []
blocked_by: []
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Split relay core runtime composition #repo/RIPDPI #area/relay #status/backlog 🔼

## Summary

Split `native/rust/crates/ripdpi-relay-core/src/runtime.rs` and the relay-core dependency surface so protocol backend composition, listener lifecycle, SOCKS dispatch, counters, errors, and telemetry are independently owned.

## Context

`ripdpi-relay-core` directly composes multiple relay protocol crates and the runtime file builds backends, validates config, accepts clients, spawns SOCKS handling, updates session counters, and shapes telemetry in one surface.

## Acceptance criteria

- [ ] Extract backend registry/composition from listener runtime.
- [ ] Extract runtime counters/error state and telemetry projection.
- [ ] Keep SOCKS session dispatch separate from backend construction.
- [ ] Document whether `ripdpi-relay-core` remains the intentional protocol composition crate or should be split further.
- [ ] Add targeted relay-core tests or compile checks for backend registration.

## Completion outcome

Closing this task means relay-core has an intentional boundary: backend registration/composition, listener runtime, SOCKS dispatch, runtime counters/errors, and telemetry projection are separate enough that one relay backend does not drag every runtime concern into review.

## Regression guardrails

- Do not hide all protocol backends behind a new broad `backend.rs` switchboard with lifecycle and telemetry logic mixed in.
- Do not make listener accept-loop changes require touching backend registration or telemetry schemas.
- Do not add new relay protocols directly to runtime code without using the registration boundary.
- Do not close the task unless the documented composition boundary matches the dependency graph.
- Do not close the task without focused unit tests for backend registration, listener/runtime orchestration, and telemetry projection slices.

## Links

- [[Epic - Post-refactor architecture cleanup]]
