---
task_id: UIX-1790432223307662
change: preserve-domain-bypass-on-invalid-draft
commit_sha: null
local: required
local_evidence: Focused app and core:data unit tests passed with -Pripdpi.skipNativeBuild=true; app/core:data detekt and ktlint passed. The delayed-flow test failed before the guard and passed after it. Combined-tree app lint and checks are pending.
remote_ci: required
remote_ci_evidence: Pending hosted CI after integration.
device: required
device_evidence: Pending editor interaction on an Android device or emulator.
artifact: not_applicable
artifact_evidence: No distributable artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-INVALID-ONLY-DRAFT | UIX-1790432541016785 | DomainBypassRepositoryTest and DomainBypassListViewModelTest passed | passed |
| REQ-EMPTY-DRAFT | UIX-1790432541016785 | DomainBypassRepositoryTest empty-list test passed | passed |
| REQ-MIXED-DRAFT | UIX-1790432541016785 | DomainBypassRepositoryTest mixed-draft test passed | passed |
| REQ-PENDING-DRAFT | UIX-1790433772090153 | Delayed-flow ViewModel regression test failed before the guard and passed after it | passed |
