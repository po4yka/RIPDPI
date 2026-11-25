---
task_id: SVC-1795615848338894
change: svc-1795615848338894-keep-active-tunnel-when-new-server-fails
commit_sha: null
local: required
local_evidence: ":core:service:testStandardDebugUnitTest (5 classes, 128 tests, 0 failures); :app:testGithubDebugUnitTest (4 classes, 63 tests, 0 failures); :core:service:detekt, :app:detekt, ktlint, :app:lintGithubDebug pass"
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this change.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SVC-1795615848338894-001 | SVC-1795615848875322 | `StandaloneAmneziaWgActivatorTest` (failed switch restarts previous VPN; cold start and user Stop do not restart), `RelayActivationSelectorReloadTriggerTest` (failed manual relay pick reverts and restarts; rejected pick does not restart) | local pass |
| REQ-SVC-1795615848338894-002 | SVC-1795615849359538 | `AmneziaWgRuntimeSupervisorTest.readinessTimeoutAfterEveryAttemptFailsWithTypedReasonWithoutRawState`, `MainConnectionResultFeedbackTest` (`failure_awg_unreachable` shown, no `state=`); string in 10 locales, `:app:lintGithubDebug` passed | local pass |
| REQ-SVC-1795615848338894-003 | SVC-1795615849834416 | Already met by `ConnectionFailureAlert` (67edcaf389): `ServiceStatusReporterTest.failedStatusEmitsFailureEvent`, `ConnectionNotificationTest` (posts only when no activity is visible) | local pass |
| REQ-SVC-1795615848338894-004 | SVC-1795615850332527 | `VpnServiceRuntimeCoordinatorTest.failedTransportReplacementClosesTunBeforeReportingRollbackSafe` plus the three new `StandaloneAmneziaWgActivatorTest` failed-switch cases | local pass |
