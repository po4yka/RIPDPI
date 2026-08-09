## Context

Connection-freeze classification currently emits one class with string tags and
a fixed stage. The block-signal matrix intentionally uses a coarse enum, while
runtime penalties prefer diversification. Existing network keys are privacy-preserving.

## Goals / Non-Goals

- Goal: preserve typed phase/direction/persistence evidence across boundaries.
- Goal: add a no-op-by-default guard for confirmed freezes.
- Non-goal: hardcode timing from research or claim censorship from one observation.

## Decisions

- Carry a typed optional refinement on classified/diagnostic evidence and keep it
  outside the coarse block-signal matrix key.
- Derive phases only from explicit wire-observable milestones; otherwise report unknown.
- Store guard state on the existing privacy-preserving network/authority record,
  never raw destination or network identifiers.
- Separate learning from selection: confirmation stamps guard state; selection
  suppresses both same-destination retry and diversification while active.

## Contracts and ownership

- Failure classifier owns phase derivation and serde-compatible types.
- Diagnostics owns Rust/Kotlin projection and bounded export.
- Runtime policy/config owns optional guard state and decision enforcement.
- Shared schema snapshots and generated bindings are serialized write lanes.

## Risks / Trade-offs

- Misclassification can suppress useful retry; require confirmation and explicit opt-in.
- Contract growth can break old archives; use optional defaulted fields and migration tests.
- Guard state may become identifying; retain only existing hashed scope and bounded timestamps.

## Migration Plan

Land optional typed evidence and round-trip tests, then add the disabled policy
and selection guard. Enable only in controlled diagnostics after classifier
precision is observed. Rollback unsets the policy and preserves legacy behavior.
