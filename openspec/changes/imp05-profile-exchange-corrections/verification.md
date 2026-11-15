---
task_id: AND-1794728744166445
change: imp05-profile-exchange-corrections
commit_sha: 80793913b0f88fcaf186c4b09448b600c0b0d206
local: passed
local_evidence: App 378, core data 97, and runtime state 50 targeted tests passed. Affected Detekt tasks, Roborazzi, ci-gates, and locked Cargo metadata passed. Independent review found no remaining integration blocker.
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: not_applicable
artifact_evidence: No release artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

The tree at `80793913b0f88fcaf186c4b09448b600c0b0d206` passed all selected checks. Subsequent changes record the results.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-PROFILE-EXCHANGE-IMPORT | AND-1794728798305125 | `RelayProfileActivatorTest` checks independent Room records, reopening, unchanged selection and rollback. Profile confirmation and SSH tests exercise admission. | Passed |
| REQ-PROFILE-EXCHANGE-INTAKE | AND-1794728798907804 | `ImportHandlerActivityTest`, decoder and scanner tests, and AWG editor tests cover routing, strict text, safe errors, timeouts and conflicts. | Passed |
| REQ-PROFILE-EXCHANGE-EXPORT | AND-1794728799395355 | Mapper, view model, clipboard tests and SIP002 userinfo checks cover representation and storage reads. | Passed |
| REQ-PROFILE-EXCHANGE-QR | AND-1794728799856931 | QR encoder and file reader tests check the four-module margin, UTF-8, matrix size, capacity and error results. Roborazzi confirms the new QR shape. | Passed |

## Local checks

- Gradle with JDK 21, `ripdpi.skipNativeBuild=true`, four workers and the machine build gate ran the App exchange, AWG, SSH and confirmation tests and four Full screenshots. Result is 378 tests with zero failures, errors or skips.
- The same run verified the 97 selected Data codec tests and 50 Runtime State URI tests. All passed with zero failures, errors or skips.
- Detekt passed for App, Data and Runtime State. `just ci-gates`, architecture health and `cargo metadata --locked` passed.
- The authorized QR screenshot change affects only pixels inside the QR region. The narrow record and verify tasks passed. The final run verified this screenshot and the three profile confirmation screenshot cases.
- Hosted CI and on-device camera, URI grants and native activation acceptance remain pending.
