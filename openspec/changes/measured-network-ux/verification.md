---
task_id: EPC-1791124000119505
change: measured-network-ux
commit_sha: null
local: required
local_evidence: Scope slice passed 105 targeted app tests, app/service locale lint, app Detekt/ktlint, architecture health and locked Cargo metadata. Remaining slices are pending.
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: No Android device is currently attached; emulator lifecycle evidence is pending.
artifact: required
artifact_evidence: Scope slice has 10 reviewed PNG fixtures including light, dark, Arabic RTL and font 2.0; narrow Roborazzi verification passed after explicitly authorized recording. Other slices and export output inspection are pending.
deployment: not_applicable
deployment_evidence: No production deployment or release is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SCOPE | EPC-1791124243600668 | 105 app UI/factory/ViewModel tests; app/service locale lint; 10 reviewed PNG fixtures and narrow Roborazzi verification; independent code review | Passed locally |
| REQ-METRICS | EPC-1791124244103077 | Pending source-derived window/freshness tests | Required |
| REQ-CONFIG | EPC-1791124244595215 | Pending applied-snapshot and lifecycle/recovery tests | Required |
| REQ-SEARCH | EPC-1791124245093725 | Pending picker filtering/selection/dismiss tests | Required |
| REQ-EXPORT | EPC-1791124245588453 | Pending cancel/failure and actual output redaction checks | Required |
| REQ-PAUSE | EPC-1791124246073345 | Pending persisted intent, stale callback and reconstruction tests | Required |
| REQ-PROFILES | EPC-1791124246556219 | Pending actual probe selection, race and local metadata tests | Required |
| REQ-ACCESSIBILITY | EPC-1791124243600668 | All slices require all-locale lint, large-font/RTL/light/dark visual review and semantic controls | Required |
