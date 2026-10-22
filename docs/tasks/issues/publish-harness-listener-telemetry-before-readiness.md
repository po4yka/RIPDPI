---
id: TST-1792693347075851
title: Publish harness listener telemetry before readiness
kind: bug
status: doing
area: testing
priority: medium
owner: Protocol implementation
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-22
updated: 2026-10-22
spec_reason: test-only
---

## Goal

Make `start_proxy` return only after its listener telemetry delegate has finished
publishing startup observations. The shared test harness currently wakes its
caller first, so the listener-address integration test can observe no address.

## Acceptance criteria

- A blocking-delegate regression fails on the old ordering and passes when the
  delegate completes before startup is published, without sleeps or retries.
- The full affected Rust crate suites and canonical coverage pass; required
  hosted checks pass before acceptance. Existing privileged/platform exclusions
  remain explicit and unchanged.
- Production runtime, public contracts, assertions, and coverage thresholds are
  unchanged.

## Ownership

Protocol implementation owns the shared proxy-runtime test telemetry helper,
its regression test, this issue and execution record. The generated board is
updated only through `taskctl`; no other writer edits these paths.

## Evidence

The existing listener-address test passed in accepted CI run `39815952976` and
failed in run `39824233967` at the same Rust sources and toolchain. The first
failure was `reported.expect("listener address")`; the harness published its
startup latch before forwarding `on_listener_started` to the address sink.
The new blocking-delegate regression was run against the old order with pinned
Rust 1.98.1: one test failed with the premature-readiness assertion, after its
worker had been released and joined. The helper now invokes the delegate before
publishing startup. Independent source review, formatting, task validation and
architecture health pass (no new, worsened or stale architecture indicators).

The exact regression, full proxy-runtime and CLI suites, and canonical coverage
with existing thresholds enforced remain pending in the shared local build
queue. Required hosted checks for this correction also remain pending. This
task stays open; the earlier atomic-AWG source acceptance is unchanged.
