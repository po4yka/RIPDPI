---
task_id: DAT-1792728666702112
change: subscription-generation-recovery
commit_sha: 991dfc30c6558e39deb2e7b48e020a877621ecef
local: passed
local_evidence: "Affected runtime-state, core:data and app unit suites passed 4247 tests with zero failures, errors or skips; staticAnalysis passed. Architecture health, task contracts, strict OpenSpec, Cargo metadata and diff checks passed. Independent production and test reviews approved."
remote_ci: passed
remote_ci_evidence: "PR 652 head 991dfc30c6558e39deb2e7b48e020a877621ecef passed ci-required and CodeQL; CI run 39903731858 completed successfully, including all three Debug/Release channels and Android API 27/33/35/36/37 instrumentation. https://github.com/po4yka/RIPDPI/actions/runs/39903731858. Rebase merge 31c8ea8250dce7374a27820d67ba39bb502b15f5 has the identical tree."
device: not_applicable
device_evidence: Storage ownership and replay are exercised through production Kotlin owners; no physical network or device acceptance is claimed.
artifact: not_applicable
artifact_evidence: No separately distributed artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AWG-OWNER | DAT-1792728974148848 | Replace refresh-only revision with all-mutation ownership generations | Passed |
| REQ-AWG-GENERATION | DAT-1792728974148848 | ABA and stored-generation regression suites | Passed |
| REQ-AWG-GENERATION-MIGRATION | DAT-1792728974148848 | Schema, pending-journal and failed preferences commit migration regressions | Passed |
| REQ-AWG-COMMIT | DAT-1792728974601219 | 40-case two-peer fault/cancellation matrix, competing edit and existing collectors | Passed |
| REQ-AWG-COMMIT | DAT-1792728975045421 | 4247 local unit tests, static/task/architecture gates and hosted CI run 39903731858 | Passed |
