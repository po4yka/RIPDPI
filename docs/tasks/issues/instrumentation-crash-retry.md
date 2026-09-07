---
id: CIC-1788781739933829
title: Retry instrumentation on emulator process crashes
kind: bug
status: done
area: ci
priority: high
owner: CI flake fix
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-09-07
updated: 2026-09-07
spec_reason: tooling-only
closed_at: "2026-09-07T17:17:01Z"
closed_reason: Retry, evidence preservation, and diagnostics contracts verified by hosted CI.
evidence_summary: "Bounded instrumentation crash retry validated in production: PR runs 34131540130 and 34131588470 logged 'Instrumentation attempt 1/2 crashed; retrying' with per-attempt evidence files, and full-route CI run 34131485706 on main 641524cdb2e8ee8764dcc10c2b3a02f2fd963b53 passed including all five android-instrumented-tests API legs; 10 unit tests plus workflow contract gates green locally."
---

## Goal

Make push-triggered CI on main resilient to emulator-level instrumentation crashes. CI run 33967607861 (push 50f120d27) failed only the android-instrumented-tests (pixel6Api37Google) job with `INSTRUMENTATION_RESULT: shortMsg=Process crashed.` while the same job passed on pull_request runs 33942374912 and 34063687974. The instrumentation target process died mid-run under GMS post-boot memory pressure; the host-side runner must treat that class as a transient emulator failure, retry a bounded number of times, and preserve per-attempt evidence. Persistent crashes must still fail the run.

## Acceptance criteria

- `prebuilt_android_instrumentation.py run` retries the `am instrument` invocation when output ends in an instrumentation process crash (`INSTRUMENTATION_RESULT: shortMsg=`), up to a fixed bound.
- Deterministic failures (failed tests, malformed output, `INSTRUMENTATION_FAILED`, non-crash errors) are never retried.
- Prior attempt outputs stay on disk as `instrumentation.attempt-<n>.txt`; the final attempt writes `instrumentation.txt` so evidence validation steps are unchanged.
- Unit tests cover crash-class retry, retry exhaustion, and no-retry for test failures.
- Emulator diagnostics capture also dumps the logcat crash buffer, and the started emulator enlarges the main logcat ring buffer so crash evidence survives GMS log flood.
- `python3 -m unittest scripts.tests.test_prebuilt_android_instrumentation` and `bash scripts/ci/test-android-emulator-helpers.sh` pass.

## Ownership

The CI flake fix role owns `scripts/ci/prebuilt_android_instrumentation.py`, `scripts/tests/test_prebuilt_android_instrumentation.py`, `scripts/ci/android-emulator-helpers.sh`, `scripts/ci/test-android-emulator-helpers.sh`, and this record in the `fix/ci-instrumentation-crash-flake` worktree. Review agents are read-only. No workflow routing, gates, or dependencies change.
