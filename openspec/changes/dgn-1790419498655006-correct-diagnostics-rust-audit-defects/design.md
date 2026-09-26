## Context

The audit spans independent Rust crates and their monitor-engine callers. `proposal.md` records the observed failures. Existing public wire data and Kotlin consumers remain unchanged.

## Goals / Non-Goals

- Goal: make emitted evidence reflect complete responses and stop work at the scan deadline.
- Non-goal: change the diagnostic catalog, Telegram target asset, UI, or unresolved domain outcome policy.

## Decisions

- Fix shared HTTP parsing once in `ripdpi-diagnostics-contracts`; reject short bodies and incomplete headers in `ripdpi-diagnostics-http`. Existing fat-header callers inherit the header fix.
- Keep required candidate capabilities as an owned list so the active strategy can express combinations. Guard baseline classification by the existing strategy status.
- Keep resolver permits with the blocking worker and preserve unread reports by rejecting a new session start.
- Recompute remaining time at each transport attempt and stop readiness waits at the deadline. Correct dormant probe parsers within their crate.

## Contracts and ownership

- Rust: `ripdpi-diagnostics-contracts`, `-http`, `-telegram`, `-candidates`, `-classification`, `-transport`, `-probes`, and `ripdpi-monitor-engine`.
- Kotlin: `core/engine` binding KDoc follows the native error contract. JNI, protobuf, storage, and wire schemas: none changed. The internal `StrategyCandidateSpec.requires_capabilities` Rust field changes from a static slice to a list; callers iterate it already.
- The diagnostics candidate writer owns task/OpenSpec files. Protocol, monitor, and transport writers own disjoint crate files. `Cargo.lock`, locale resources, baselines, and generated files remain unchanged.

## Risks / Trade-offs

- Rejecting incomplete or failed responses can lower previously inflated success rates. Focused tests cover short bodies, failed HTTP statuses, report reuse, resolver saturation, and deadlines.
- Existing callers may rely on an implicit report discard. The caller receives a start error and can explicitly consume the report before retrying.

## Migration Plan

No persisted or wire migration is needed. Commit each verified fix separately in its job worktree, rebase on the integration tree, then use a fast-forward merge into `main`. Validate with focused `cargo test --locked` crates, affected monitor-engine tests, `cargo metadata --locked`, and architecture-health. Rollback is a revert of the independent fix commit if a regression is confirmed.
