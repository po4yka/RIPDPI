---
task_id: DAT-1791480001436232
change: recover-renewed-subscriptions
commit_sha: 151c8ea881a21e765717d8e86c5261204a61b9d6
local: passed
local_evidence: 38 targeted app tests and app ktlint/detekt passed. After user authorization, the two expired-state screenshots were recorded and both subscription screenshot suites passed (5 tests, 10 images).
remote_ci: not_applicable
remote_ci_evidence: Hosted CI is reported separately after push.
device: not_applicable
device_evidence: Pure refresh coordination; no tunnel lifecycle changes.
artifact: not_applicable
artifact_evidence: No release artifact requested.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SUB-MANUAL-RECOVER | DAT-1791480055272167 | SubscriptionRecoveryTest, 6 cases | Passed |
| REQ-SUB-RECOVERY-SAFETY | DAT-1791480055272167 | Recovery, delivery, worker, coordinator, and rendered status-screen tests | Passed |

## Observed local checks

- RED: SubscriptionRecoveryTest ran 6 tests with 4 expected failures before the coordinator change. SubscriptionStatusScreenTest ran 4 tests with 1 expected missing-button failure before the UI change.
- GREEN: `:app:testGithubFullDebugUnitTest` with filters `*SubscriptionRecoveryTest`, `*SubscriptionStatusScreenTest`, `*SubscriptionRefreshCoordinatorTest`, `*SubscriptionRefreshDeliveryTest`, and `*SubscriptionAutoUpdateWorkerTest`: 38 tests, no failures, errors, or skips.
- `:app:ktlintCheck :app:detekt` passed on the final source. The final invocation completed in 57 seconds.
- Robolectric rendered the status screen, clicked the expired long-lived refresh action, and checked that bootstrap has no refresh action. No golden fixtures were changed.
- Gradle used `-Pripdpi.skipNativeBuild=true`, four workers, 5 GiB Gradle and 3 GiB Kotlin heap limits, and 15-second HTTP timeouts. No hosted CI, native runtime, device, or VPS acceptance is claimed.

## Completed visual gate

The recovery behavior and final combined application tests passed on `7a4a87a9d90e1575264ad3f3ae643e9580fbec4f`. Initial snapshot verification found the expected new refresh action on the expired long-lived subscription. Read-only comparison rendered both themes before the user authorized the two baseline updates.

- On source `024e0cae14af0f81867f2de8259c9428c7e4a131`, `:app:recordRoborazziGithubFullDebug --tests 'com.poyka.ripdpi.ui.screenshot.SubscriptionStatusScreenshotTest.expiredSubscription'` updated only `expired_light.png` and `expired_dark.png`.
- Both images were visually reviewed. The refresh button and the larger card are the only changes. Neither image has clipped or overlapping content. Each image is byte-identical to its previously reviewed actual artifact.
- `:app:verifyRoborazziGithubFullDebug --tests 'com.poyka.ripdpi.ui.screenshot.SubscriptionStatusScreenshotTest' --tests 'com.poyka.ripdpi.ui.screenshot.SubscriptionImportConfirmScreenshotTest'` passed: 5 tests, 10 images, no failures, errors, or skips.
- Both commands used `-Pripdpi.skipNativeBuild=true -Pripdpi.includeRoborazziUnitTests=true -Pripdpi.nativeCpuBudget=2 --no-daemon --max-workers=2`, 5 GiB Gradle and 3 GiB Kotlin heaps, and 15-second HTTP timeouts.
- The snapshot writer changed no application source or other golden family. Commit `151c8ea881a21e765717d8e86c5261204a61b9d6` contains the verified images. Fetch and rebase against `origin/main` left that commit unchanged.
