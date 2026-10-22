---
task_id: "DAT-1792661188293948"
change: "offline-reserve-profiles"
commit_sha: "33046c961cf4a501a1a8ac35243c6a171424fdc3"
local: "passed"
local_evidence: "Formatting, diff whitespace, task/OpenSpec validation, architecture-health and normal commit hooks passed. Local Full/Simple source and test compilation passed, but the Full test run exhausted disk space in Robolectric temporary files and Gradle reporting; Simple did not execute. That local runtime attempt is not acceptance; complete hosted JVM and emulator runs provide runtime evidence."
remote_ci: "passed"
remote_ci_evidence: "CI run 39792086326 attempt 1 passed on 6ec802d16d19a5c0f81f817be8cfc8f1205d29b5: 46 successful and 18 intentionally unrouted jobs, zero failed or pending jobs. Full and Simple JVM tests, Kotlin coverage, static analysis, all native gates and API 27/33/35/36/37 Full and Simple instrumentation passed. API 35 also passed JNI strategy and real Xray provider TUN acceptance. ci-required and CodeQL passed. Rebase-merged 33046c961cf4a501a1a8ac35243c6a171424fdc3 has the identical complete tree 8d205fb96495d83294b14ef9bd5f90dc49f98d71."
device: "not_applicable"
device_evidence: "Physical-device acceptance is outside this scope. Hosted emulator evidence is recorded under remote_ci; filtered-network acceptance is not claimed."
artifact: "not_applicable"
artifact_evidence: "No release artifact is owned by this storage change; complete CI build evidence is recorded above."
deployment: "not_applicable"
deployment_evidence: "No server deployment or credential action was requested or performed."
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RESERVE-RELOAD | DAT-1792661307731110 | OfflineReservePayloadIntegrationTest restart and real TLS/SOCKS qualification; SelectorColdStartupTest | Passed |
| REQ-RESERVE-STORAGE | DAT-1792661302262545 | ProxyGroupOfflineReserveTest and repository concurrency/corruption/read-write regressions | Passed |
| REQ-RESERVE-REFRESH | DAT-1792661307171939 | SubscriptionRefreshCoordinatorTest and SubscriptionRefreshDeliveryTest invalid/stale refresh regressions | Passed |

## Source and CI evidence

Tested source: `6ec802d16d19a5c0f81f817be8cfc8f1205d29b5`. PR 645 was rebase-merged as
`33046c961cf4a501a1a8ac35243c6a171424fdc3`. Both complete Git trees are
`8d205fb96495d83294b14ef9bd5f90dc49f98d71`; the comparison is empty and contains no unrelated main changes.

CI 39792086326 attempt 1 passed with 46 successful and 18 intentionally unrouted
jobs, zero failed or pending jobs. Evidence includes JVM job 119871905296,
coverage 119871905701, static analysis 119871344251 and ci-required 119895349234.
The full emulator jobs passed on API 27 (119886816494), 33 (119886816402),
35 (119886816584), 36 (119886816512) and 37 (119886816505), including Simple
integration. API 35 additionally passed JNI strategy and real Xray provider TUN
acceptance. CodeQL run 39792086294 passed.

## Acceptance incident and resolution

The prior closure was reverted after CI 39772247126 exposed a cold-start
catalogue deadlock on all five API levels. The corrected lifecycle explicitly
loads the validated catalogue before awaiting its observation seed. Read errors
propagate without publication or mutation and allow a later recovery attempt.
`SelectorColdStartupTest` covers empty startup, persisted selection and read-error
recovery. The complete corrected JVM and emulator matrix above passed without
weakening timeouts, assertions or safety gates.

The subsequent JVM run exposed two test-boundary defects: the routing-editor
fake delayed the old observation API instead of the explicit read, and an
exception identity assertion crossed coroutine stack recovery. The corrected
fake gates the actual read and verifies both rejection before hydration and a
successful save afterward; original exception identity is checked inside the
timeout boundary. Existing no-publication/no-write/no-refresh assertions remain.

## Scope and local limitation

Local formatting, architecture and task checks passed. The local full app test
attempt exhausted disk space after both variants compiled; its incomplete test
results are not acceptance. Hosted complete runs passed. Real HTTPS/SOCKS tests
use local TLS servers and test storage ports; they are not physical-device,
platform-keystore or filtered-network proof. No server deployment or credential
action was performed.
