---
task_id: DAT-1791480001436232
change: recover-renewed-subscriptions
commit_sha: b6878fdbeedc4f36153033e463702fb934379fb6
local: blocked
local_evidence: 38 targeted app tests and app ktlint/detekt passed. Snapshot verification found one expected expired-state difference; golden approval and repeat verification are pending.
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

## Remaining visual gate

The recovery behavior and final combined application tests passed on `7a4a87a9d90e1575264ad3f3ae643e9580fbec4f`. Snapshot verification still has one expected expired-state difference because the renewed-subscription refresh action is now visible. Approval to update that golden and a successful repeat snapshot verification are pending. This task remains open; no golden image was changed by the evidence worker.
