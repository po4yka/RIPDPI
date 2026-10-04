---
task_id: EPC-1791124000119505
change: measured-network-ux
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: No Android device is currently attached; emulator lifecycle evidence is pending.
artifact: required
artifact_evidence: Disposable visual actuals and export output inspection are pending.
deployment: not_applicable
deployment_evidence: No production deployment or release is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SCOPE | EPC-1791124243600668 | Pending scan UI/factory tests and visual review | Required |
| REQ-METRICS | EPC-1791124244103077 | Pending source-derived window/freshness tests | Required |
| REQ-CONFIG | EPC-1791124244595215 | Pending applied-snapshot and lifecycle/recovery tests | Required |
| REQ-SEARCH | EPC-1791124245093725 | Pending picker filtering/selection/dismiss tests | Required |
| REQ-EXPORT | EPC-1791124245588453 | Pending cancel/failure and actual output redaction checks | Required |
| REQ-PAUSE | EPC-1791124246073345 | Pending persisted intent, stale callback and reconstruction tests | Required |
| REQ-PROFILES | EPC-1791124246556219 | Pending actual probe selection, race and local metadata tests | Required |
| REQ-ACCESSIBILITY | EPC-1791124243600668 | All slices require all-locale lint, large-font/RTL/light/dark visual review and semantic controls | Required |
