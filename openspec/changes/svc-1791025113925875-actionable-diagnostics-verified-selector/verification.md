---
task_id: SVC-1791025113925875
change: svc-1791025113925875-actionable-diagnostics-verified-selector
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: not_applicable
artifact_evidence: No distributable release artifact is owned; build and device proof are recorded separately.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-ADS-ACTIONS | SVC-1791025380381572 | Pending recommendation generator, navigation and interaction tests plus locale lint. | required |
| REQ-ADS-SELECTION | SVC-1791025380877118 | Pending offline-choice/start and active-switch/cancellation tests plus device smoke. | required |
| REQ-ADS-PAYLOAD | SVC-1791025381376340 | Pending candidate transport fixtures for valid response, stall, failure and stale evidence. | required |
| REQ-ADS-LOCALITY | SVC-1791025379377245 | Pending native tests, metadata and architecture checks. | required |
| REQ-ADS-LOCALITY | SVC-1791025379880611 | `build-gate -- env CARGO_BUILD_JOBS=3 ./gradlew :core:diagnostics:testDebugUnitTest :app:testGithubFullDebugUnitTest -Pripdpi.skipNativeBuild=true --max-workers=4`: passed; standalone ktlint and independent semantic review passed. | passed |
| REQ-ADS-LOCALITY | SVC-1791025381882198 | Pending combined static analysis, architecture, hosted CI and device evidence. | required |
