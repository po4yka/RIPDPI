---
task_id: DAT-1791480858535857
change: fetch-subscription-on-confirm
commit_sha: null
local: passed
local_evidence: 67 app tests passed; app ktlintCheck and detekt passed.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI is reported separately after push.
device: not_applicable
device_evidence: Refresh and confirmation logic is covered by app unit tests; no tunnel change.
artifact: not_applicable
artifact_evidence: No release artifact requested.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SUB-INITIAL-FETCH | DAT-1791480910766271 | SubscriptionInitialImportTest with real coordinator and MockWebServer | Passed |
| REQ-SUB-INITIAL-FAILURE | DAT-1791480910766271 | SubscriptionInitialImportTest with real coordinator and MockWebServer | Passed |
| REQ-SUB-INITIAL-BOOTSTRAP | DAT-1791480910766271 | SubscriptionImportConfirmViewModelTest bootstrap cases | Passed |

## Observed local checks

- RED: `:app:testGithubFullDebugUnitTest` with the new `*SubscriptionInitialImportTest` and the preceding recovery, coordinator, delivery, worker, and status-screen filters: 44 tests, exactly 6 new initial-import failures. All 38 recovery regression cases passed after the fingerprint rebase.
- GREEN: the same filters plus `*ImportConfirmViewModelTest` and `*SubscriptionAwgRefreshTest`: 67 tests passed, with zero failures, errors, or skips.
- `:app:ktlintCheck :app:detekt` passed on the final source; the final invocation completed in 36 seconds.
- Real HTTP fixtures prove no request before confirmation, profiles persisted before success, failed and empty responses without success, same-group retry and renewal, and AWG-only persistence. Cancellation produces no false success or error. Existing consumed-bootstrap reconfirmation tests passed.
- Gradle used `-Pripdpi.skipNativeBuild=true`, four workers, 5 GiB Gradle and 3 GiB Kotlin heaps, and 15-second HTTP timeouts. Hosted CI, native builds, device, and VPS acceptance were not run.
- Independent review approved the final implementation and test scope.
