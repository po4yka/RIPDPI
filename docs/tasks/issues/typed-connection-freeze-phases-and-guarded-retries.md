---
id: DGN-1786299627046211
title: Implement typed connection-freeze phases and guarded retries
kind: feature
status: backlog
area: diagnostics
priority: high
risk: high
owner: Failure diagnostics
parent: null
blocked_by: []
spec_mode: required
openspec_change: dgn-1786299627046211-typed-connection-freeze-phases-and-guarded-retries
created: 2026-08-09
updated: 2026-08-09
related_tasks: []
---

## Goal

Distinguish pre-handshake silence, handshake-stage freezing, and post-data stalls
as typed evidence, then prevent confirmed freeze observations from triggering
immediate same-destination retries or uncontrolled strategy diversification.

## Ownership

- Primary surfaces: failure-classifier types, diagnostics mapping, runtime retry
  policy/configuration, Kotlin projections, privacy-safe exports, and focused tests.
- Serialized lanes: diagnostics wire/schema snapshots and shared runtime policy
  contracts have one writer at a time.

## Acceptance criteria

- Freeze phase, direction, persistence, and repeatability are typed and survive
  Rust/Kotlin serialization without widening the coarse block-signal matrix key.
- The classifier does not label every post-handshake failure as a freeze and
  preserves explicit unknown evidence when observations are insufficient.
- A confirmed freeze can activate a configurable, disabled-by-default retry
  guard scoped to the privacy-preserving network/authority identity.
- While guarded, the runtime suppresses both immediate same-destination retry
  and diversification that would change the transport fingerprint.
- Existing behavior is byte- and decision-equivalent when the guard is unset;
  no duration from external research is embedded as a default.

## Verification

- Focused failure-classifier, runtime-policy, serialization, and Kotlin tests
- Diagnostics schema/API snapshot and privacy boundary gates
- `just task-check` and the affected locked Cargo/JVM checks
