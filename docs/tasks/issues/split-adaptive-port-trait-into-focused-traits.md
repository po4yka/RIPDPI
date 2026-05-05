---
title: Split AdaptivePort into AdaptiveHintPort, AdaptiveFeedbackPort, and RetryPacingPort
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

- [ ] #task Split AdaptivePort into AdaptiveHintPort, AdaptiveFeedbackPort, and RetryPacingPort #repo/RIPDPI #area/rust-native #status/backlog ⏫

## Objective

Split the 27-method `AdaptivePort` trait into three focused traits — `AdaptiveHintPort`, `AdaptiveFeedbackPort`, and `RetryPacingPort` — so callers depend only on the slice they use and the 415-LOC impl file is split into focused delegators.

## Context

`AdaptivePort` (adaptive_port.rs:17–232) spans hint resolution (3 methods), adaptive feedback (9 `note_*` methods), strategy-evolution hints (2 methods), strategy-evolution feedback (3 methods), morph policy (2 methods), preferred targets/direct path/network scope (3 methods), and retry pacing (4 methods). Single implementor: `ServicesStateHandle`. Callers that only resolve hints still depend on the full 27-method surface. The 415-LOC `adaptive_port_impl.rs` mechanically acquires different `RwLock` fields per method with no business logic — it is large only because the trait is large.

Source: `native/rust/crates/ripdpi-runtime-adaptive/src/adaptive_port.rs:17-232`
Impl: `native/rust/crates/ripdpi-runtime-services/src/adaptive_port_impl.rs`

## Acceptance criteria

- [ ] `AdaptiveHintPort`: `resolve_tcp_hints`, `resolve_udp_hints`, `resolve_fake_ttl`, and both `_with_evolver` variants (5 methods).
- [ ] `AdaptiveFeedbackPort`: all `note_*` TCP/UDP/fake-TTL success/failure/server-TTL methods (9 methods) plus strategy-evolution feedback (3 methods).
- [ ] `RetryPacingPort`: `note_retry_success`, `note_retry_failure`, `build_retry_penalties`, `apply_retry_pacing` (4 methods).
- [ ] Morph policy and preferred-target/network-scope methods go into `MorphPolicyPort` or stay in a reduced `AdaptivePort` — decision recorded in commit message.
- [ ] `ServicesStateHandle` implements all new traits; `adaptive_port_impl.rs` is split into focused files of ≤100 LOC each.
- [ ] All callers updated to depend on the narrowest trait they need.
- [ ] `cargo nextest run` green.

## Definition of done

`adaptive_port.rs` replaced by ≥3 focused trait files; impl files ≤100 LOC each; no call site imports the full 27-method trait unnecessarily.
