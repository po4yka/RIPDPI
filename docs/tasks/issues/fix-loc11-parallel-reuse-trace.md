---
id: TST-1794127602775562
title: Preserve carrier reuse traces during parallel tests
kind: bug
status: done
area: testing
priority: medium
owner: relay-trace
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-08
updated: 2026-11-08
spec_reason: test-only
closed_at: "2026-11-08T09:08:02Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Trace capture fixed at cbedaf8a833707c59ef3b2f1cd5fe338cee8589a. Deterministic red 0 vs 1; trace tests 4/4 and original mux group 4/4 with five fresh-process repeats. Combined relay-core 218 passed and two existing external-peer tests ignored; relay-mux 72/72, session-limit 8/8. Independent review, all-target clippy and source gates passed.
---

## Goal

Record VLESS carrier reuse in the attempt trace when other tests emit the same event without a subscriber.

## Acceptance criteria

- Reproduce the missing `carrier_reused` event with the existing parallel test group.
- Fix the proved instrumentation cause without serializing or disabling tests.
- Keep the real loopback carrier reuse and handshake-stage assertions.
- Pass the relay trace tests and a repeated parallel mux test group.

## Ownership

The relay-trace writer owns the relay attempt trace tests and this task in the isolated `fix/loc11-trace` worktree. Proxy runtime files belong to the separate session-accounting writer. No dependency, baseline, golden, or schema changes are permitted.


## Verification

- The original parallel mux group failed with three passes and one missing reuse event.
- A new focused regression failed before the fix: a callsite first used on an unobserved thread stayed disabled for the scoped subscriber.
- The test capture now keeps observed and unobserved dispatchers registered. This avoids the single-dispatch cache path in pinned tracing-core 0.1.36. Production telemetry uses the host subscriber and is unchanged.
- `cargo test -p ripdpi-relay-core --lib --locked tests::relay_attempt_trace::trace_capture_records_a_callsite_first_used_without_a_subscriber -- --exact`: one pass after the fix.
- `cargo test -p ripdpi-relay-core --lib --locked vless_reality_mux_`: four passes with the default parallel runner. The same built test binary passed this group in five fresh processes.
- The built test binary passed all four `tests::relay_attempt_trace::` tests together.
- The changed file passes the pinned Rust formatter. No dependency or baseline changed.
