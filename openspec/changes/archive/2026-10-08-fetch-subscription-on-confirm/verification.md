---
task_id: DAT-1791480858535857
change: fetch-subscription-on-confirm
commit_sha: bbc7449b73c690a4bf40eedad1db82b9d84ad182
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

## Combined tree verification

The final combined gate passed on `7a4a87a9d90e1575264ad3f3ae643e9580fbec4f`: 269 targeted tests (134 core data, 10 runtime state, 10 service, 24 Simple seeder, and 91 Full app), with zero failures, errors, or skips. The runtime-state tests were up to date on unchanged source. `staticAnalysis` passed in the same invocation: 816 tasks, 167 executed, 5 from cache, and 644 up to date. Architecture health reported 21 unchanged indicators with no new or worsened entries; `cargo metadata --manifest-path native/rust/Cargo.toml --locked` passed. The integration owner supplied these results and the local Gradle log `/tmp/awg-p2-rebased-fresh.log`. Hosted CI, device, native artifact, and live-server results are not inferred.

The previous combined tree `8ff34e99bc60970a77a3d35e6a67aa35f0905b03` also passed 269 targeted tests and `staticAnalysis`. The first run after rebase failed because the disk was full and was followed by heap and result serialization failures. It was superseded by the successful repeat gate above.
