---
task_id: DAT-1792661188293948
change: offline-reserve-profiles
commit_sha: 5be355fd0397dfea71f7e68ec790a29c568be174
local: passed
local_evidence: "Explicit-file ktlint, git diff --check, taskctl/OpenSpec validation and architecture-health passed. Architecture has 21 baseline indicators and zero new, worsened or stale entries. Local Gradle attempts could not acquire the shared build gate; the later combined attempt timed out before Gradle and reported a wrapper syntax error. Compiled JVM/static checks are evidenced under remote_ci."
remote_ci: passed
remote_ci_evidence: "Exact source 5be355fd0397dfea71f7e68ec790a29c568be174: CI 39768539174 jobs build-android-tests 119791123903, kotlin-coverage 119791124405, gradle-static-analysis 119790735839, architecture-health 119790735937 and task-contracts 119790640414 passed; fleet-fixtures 39768539085/job119789975065 passed. These are scoped completed jobs, not a claim that the entire source run had finished. Final PR checks remain required before merge."
device: not_applicable
device_evidence: "Real local TLS/SOCKS and restart tests establish source-level behavior. Physical-device, platform AndroidKeyStore, native-relay-engine and filtered-network acceptance are not claimed."
artifact: not_applicable
artifact_evidence: "No release artifact is requested. Exact-source test reports are retained as test-results artifact 12421608673, SHA-256 4f7161b0830e2105bdf0b9a46e723e5593dedff3dcb43f5776d68f74e82b6cf6."
deployment: not_applicable
deployment_evidence: User explicitly excludes server deployment.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RESERVE-RELOAD | DAT-1792661307731110 | OfflineReservePayloadIntegrationTest reloads persisted reserves, selects a complete HTTPS/SOCKS reserve after a truncated primary and preserves selection across another restart; all-bad reserves retain the prior selection. Full and Simple suites passed in job 119791123903. | passed |
| REQ-RESERVE-STORAGE | DAT-1792661302262545 | ProxyGroupOfflineReserveTest and ProxyGroupBlobStoreFailureTest cover encrypted catalogue reload, corrupt/read/write failures and observers started before loading; runtime-state/core-data suites passed in job 119791123903. | passed |
| REQ-RESERVE-REFRESH | DAT-1792661307171939 | SubscriptionReserveRefreshTest and SubscriptionAwgRefreshTest cover unusable, obsolete and malformed-policy refresh results, authoritative replacement and rejection before side effects; app suites passed in job 119791123903. | passed |

## Observed checks and scope

- Local formatting, diff whitespace, task/OpenSpec contracts and architecture-health passed.
- Hosted `testDebugUnitTest` covered all library modules, including runtime-state, core-data and core-service. Hosted `:app:testGithubFullDebugUnitTest :app:testGithubSimpleDebugUnitTest`, `coverageReport` and `staticAnalysis` passed on the exact source SHA above. JVM commands used the existing CI `-Pripdpi.skipNativeBuild=true` setting.
- The positive offline integration uses actual local TLS/SOCKS transfers and a temporary-file catalogue port. It does not invoke AndroidKeyStore or the native relay engine. No device or live-network result is inferred from those fixtures.
- Observation streams perform no reads and publish no initial empty fallback. Guarded loading publishes a validated snapshot under the mutation mutex; corrupt-storage and startup-retry tests start observers before loading.
- SHARE exports remove payload control URLs. Invalid present payload policies cannot fall back to AWG import. Shared candidate queue waiting occurs outside each payload round's deadline; concurrency and cancellation regressions passed.
- Earlier local Gradle attempts never started because the machine-wide gate was occupied. No local JVM or local `staticAnalysis` pass is claimed. The hosted results provide the compiled acceptance evidence.

No server, credential or physical-device action was performed.
