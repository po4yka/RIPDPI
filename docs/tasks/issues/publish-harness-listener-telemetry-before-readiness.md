---
id: TST-1792693347075851
title: Publish harness listener telemetry before readiness
kind: bug
status: done
area: testing
priority: medium
owner: Protocol implementation
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-22
updated: 2026-10-23
spec_reason: test-only
closed_at: "2026-10-22T20:37:16Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Accepted source 40c46dba03153607cbc9c9c500d025bb2dd09b06 is tree-identical to tested head 0f80db4ff3c182efb9a25a424a754d6c5343d1b3. Deterministic red/green passed. The full Linux workspace passed 5831 tests, four privileged BPF tests and one startup smoke test. Canonical coverage passed at 86.57 percent with the unchanged 78 percent minimum enforced and zero uncovered critical files. CI 39834193370 attempt 1 passed 46 jobs with 18 routed skips. The local macOS daemon cleanup failure remains in the committed review evidence.
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

Accepted main `40c46dba03153607cbc9c9c500d025bb2dd09b06` has the same complete
tree `1a1150116d34dca6dc7b8632c0ea278932d30ba0` as tested PR #649 head
`0f80db4ff3c182efb9a25a424a754d6c5343d1b3`. CI `39834193370`, attempt 1,
passed all 46 jobs with 18 routed skips and no failed or pending jobs, including
`ci-required`, on that exact head. All 71 PR checks completed: 53 passed and 18
skipped. The full Linux workspace ran 5,831 passing tests (59 existing skips),
four privileged BPF tests and one startup smoke test. All six shared-helper
consumers passed the regression. Canonical Linux coverage passed at 86.57%,
with the unchanged 78% minimum enforced and zero uncovered critical files.

Local verification observed the deterministic red/green regression, the full
proxy-runtime suite (305 passed, 13 existing ignored), CLI packet-smoke target
(24 passed, 2 existing ignored), and canonical coverage (1,085 passed, 16
existing ignored; 86.55%, minimum enforced, zero uncovered critical files).
The broader local CLI run recorded 329 passed, one failed and 15 ignored:
`daemon_mode_writes_pid_file_and_removes_it_after_sigterm` timed out waiting for
PID-file cleanup on macOS. That unchanged test does not use the helper; the
exact-head Linux workspace ran it unfiltered and without retry, passing in
0.012 seconds. The local failure remains a verification limitation, not a
passing local suite or a diagnosed production defect. No runtime fix is claimed.

Existing exclusions are unchanged; optional packet captures and Linux-only
privileged tests are not local macOS evidence. All five Android API matrices,
JNI strategy and real Xray-provider TUN acceptance passed in hosted CI. Cloud
code/security reviews could not run because of quota; independent source review
approved the change, and no cloud approval is claimed. This test-only acceptance
does not claim physical-device, filtered-network or deployed-server verification.
