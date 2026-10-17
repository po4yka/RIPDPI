---
task_id: UIX-1792222949017657
change: ui-motion-first-wave
commit_sha: e1ed3cc2dee2fd894f3469386a143109b2c5f0ed
local: passed
local_evidence: 113 targeted unit tests passed with no failures, errors, or skips. Full and service lint, staticAnalysis, architecture health, locked Cargo metadata, DESIGN verification, and independent review passed on the rebased implementation.
remote_ci: required
remote_ci_evidence: First main push 65f5ac51e5f8c381b132bae9b2bed5a365b140fb started CI and CodeQL. Locale parity passed. Translation export failed on the two new keys; the owning generator added exactly those keys and its three discovery tests and manifest check passed locally. The follow-up hosted run is pending.
device: passed
device_evidence: API37 arm64 pool emulator-5554 ran all 4 ActionFeedbackInstrumentedTest methods successfully in 12.204 seconds. The Arabic font2 RTL control test also passed in 1.752 seconds. MainActivity ru/light timer and ar/dark/font2 layout were inspected; crash buffers were empty. The pool lease was released and font scale and locale were restored.
artifact: passed
artifact_evidence: Full arm64 debug APK SHA256 333313261c4c9bc949344bfb831ad7515546724c50eb850d0a0ca77f7ff397bc; test APK SHA256 97d0075343901780d6f803b83a20c800dede72ebfb8c9a95e00c874df5fc1fc4. Both were built from the clean commit above with verified native inputs and source-built pluggable transports.
deployment: not_applicable
deployment_evidence: No deployment is owned by this Android UI change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UAF-STATIC | UIX-1792223013470376 | Theme and control behavior tests, static-mode emulator inspection | Passed |
| REQ-UAF-DISCONNECT | UIX-1792223013470376 | Confirmation, expiry, late press and disabled-motion tests | Passed |
| REQ-UAF-OUTCOME | UIX-1792223014118844 | Active, success, failure, cancellation and saved-result tests | Passed |
| REQ-UAF-LATEST | UIX-1792223014868002 | Event insertion, reader position, jump action and filter tests | Passed |
| REQ-UAF-ACCESS | UIX-1792223015547663 | Theme tests, locale lint, static analysis, font2/RTL emulator checks and review | Passed |
| REQ-UAF-ACCESS | UIX-1792223016361525 | Rebased combined gates, main integration and remote SHA | Passed |

## Planned commands

Run focused :app:testGithubFullDebugUnitTest filters for motion, actuator,
diagnostics outcome and history; all theme tests; :app:lintGithubFullDebug;
:core:service:lintDebug; staticAnalysis; scripts/ci/check_architecture_health.py;
and cargo metadata --manifest-path native/rust/Cargo.toml --locked.
Build the real :app:assembleGithubFullDebug APK, record its SHA256, and validate
the four behaviors under an exclusive API37 arm64 emulator lease. Inspect light,
dark, font scale 2.0 and RTL without public network or production operations.
Do not bless screenshot fixtures. Record any intentional screenshot mismatch.

## Local results

The combined command passed after rebase onto `571cc3cc7`. It ran the focused
unit suite, Full and service lint, `staticAnalysis`, and both Full debug APK
assembly tasks. The unit suite ran 113 tests with no failures, errors, or skips.
The build took 6 minutes 46 seconds. Architecture health found no new or worsened
indicators. Locked Cargo metadata and the DESIGN validator passed.

The motion regression failed before the fix: a disabled spring still had a
nonzero duration. The countdown regression failed because no remaining-time
label existed. The diagnostics regression failed because the active summary
was removed before the transition. The history burst regression failed because
the second batch did not reach the newest event during a partial scroll.
All these regressions pass in the combined suite. History also covers a manual
swipe, a jump with automatic following disabled, reader position, and filters.

Independent review found no remaining actionable defects. It did not verify
TalkBack or hardware scroll input. No golden fixture or quality baseline changed.

Local logs, XML results, the artifact manifest, and device evidence are stored
in the task evidence directory under `ripdpi-ux-motion-implementation` in the
2026-10-17 visualization workspace. Main publication is recorded below.

## Device results

The exact APKs above were installed under an exclusive API37 arm64 pool lease.
All four `ActionFeedbackInstrumentedTest` methods passed in 12.204 seconds, with
no failures or skips. A separate run of the font2 RTL control method with Arabic
framework resources passed in 1.752 seconds. Both crash buffers were empty.

The real MainActivity in Russian and the light theme showed the four-second
confirmation, then three seconds and a shorter bar. The control returned to
Disconnect after expiry while the debug Connected state stayed active. The
Arabic dark Home layout at font scale 2.0 had readable controls and correct
system insets. Its connected debug preset did not persist across launch retries;
the Arabic countdown was inspected through the production control test instead.

The component tests use controlled UI states. The manual Home check uses debug
automation. A real VPN or network scan was not started. The transition test
checks intermediate and final UI states; its early screenshot is not a timing
measurement. The test host has no screen scaffold, so its status-bar overlap is
outside the app layout check. MainActivity has the correct insets. TalkBack and
hardware scroll interruption remain untested.

The lease was released. Font scale was restored to 1.0, app locale to English,
and the app was stopped. No foreground service remained.

## Main integration and publication

The job branch was rebased onto `origin/main`. The combined checks above passed
after that rebase. The clean main checkout then accepted a fast-forward merge
to `65f5ac51e5f8c381b132bae9b2bed5a365b140fb`. The authorized `git push origin main`
succeeded, and `git ls-remote` returned that exact SHA. Production and test
sources match the tested artifact commit; later receipts change documentation.

GitHub accepted the direct push and reported the existing PR, required CI, and
CodeQL rule bypass. No repository rule was changed. Hosted CI is a separate
gate and this task stays in review until it passes.

The first hosted translation-export gate found two missing manifest entries.
`scripts/ci/export-strings-for-translation.sh` regenerated the manifest and
added exactly the two new action-feedback keys. Its three discovery tests and
`scripts/ci/check-translation-export.sh` passed. This catalog metadata correction
does not change the APK resources or code. It is included in the follow-up push.
