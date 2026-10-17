---
task_id: UIX-1792259264757428
change: haptic-feedback
commit_sha: 2099cbb498813c5f2c74264cbb65751b0ff01021
local: blocked
local_evidence: Behavioral tests, static analysis, locale lint, architecture and locked Cargo metadata pass. Strict screenshot verification needs authorization for 14 reviewed fixtures; no fixtures have changed.
remote_ci: required
remote_ci_evidence: null
device: blocked
device_evidence: The first API 37 run passed seven of eight Full tests and found a test host defect. Commit 2099cbb fixes the host. Its repeat stopped before boot because the host disk is full; Full and Simple acceptance remain required.
artifact: blocked
artifact_evidence: Four genuine arm64 APKs were built and hashed at 2099cbb. All 14 intended screenshot changes were reviewed, but their fixture update is not yet authorized.
deployment: not_applicable
deployment_evidence: No deployment is owned by this app preference change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-HAPTIC-PREFERENCE | UIX-1792259354389850 | Storage and backup tests pass; Full and Simple behavior cohorts each pass 432 tests; on-device restart is still required | host passed; device blocked |
| REQ-HAPTIC-CONNECTION | UIX-1792259354945871 | Actual Running, acceptance, cancellation, equal retries, IO failure, restored state and deferred failure race regressions pass | host passed |
| REQ-HAPTIC-RESULT | UIX-1792259354945871 | Detection and diagnostics once-only, lifecycle, Off, IO and cancellation tests pass | host passed |
| REQ-HAPTIC-VALIDATION | UIX-1792259355458709 | Full, Simple and service lint pass; 14 complete actual/diff images reviewed; repeat emulator acceptance and authorized fixture verification remain required | blocked |

## Observed local evidence

- At c8f66c79a, Full and Simple each passed 432 tests with no failures, errors or skips. The final narrow Main and diagnostics cohort passed all 25 tests. The RED runs reproduced duplicate suppression, stale state and disabled feedback defects before the fixes.
- At 2099cbb, one combined Gradle run passed static analysis, Full and Simple app lint, service lint, and all four debug APK assembly tasks: 929 tasks, build successful in 8 minutes 8 seconds.
- Architecture validation passed with 21 existing findings and no new or worse findings. Locked Cargo metadata resolved all 116 workspace crates. The native inputs were checked against their producer and all 16 input hashes matched.
- The Android test host now uses FrameLayout so Material can find its ripple container. Test bodies, assertions and feedback recording overrides did not change.
- Local receipts and logs are under `.artifacts/haptic-feedback/`: `combined-unit-summary.json`, `combined-gates/`, `host-fix-gates/`, `host-fix-scope.json`, and `apk-receipt-2099cbb49.json`. These generated files are not committed.

## Remaining gates

- Complete the eight Full and eight Simple instrumented tests and real UI checks under the command-scoped emulator lease. Check System/Off, plain restart, Activity recreation, large font, RTL, installed APK hashes and environment restoration.
- Obtain fixture authorization, update only the 14 reviewed PNG files with the golden specialist, and pass strict screenshot verification.
- Fetch and rebase, repeat the applicable combined-tree gates, integrate main, push, and inspect hosted CI for the exact published SHA.
- Device tests count platform calls. They do not establish tactile quality on physical hardware.
- Runtime failure events do not contain a command ID. Authority snapshots distinguish sequential starts; they do not add protocol-level correlation to late native events.

## Publication

UIX-1792259356008226 owns independent review, combined gates, rebase, authorized main integration, push and exact-source hosted CI inspection. Main has not been changed or pushed. Hosted CI has not validated this feature SHA.
