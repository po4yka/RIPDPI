---
task_id: EPC-1791124000119505
change: measured-network-ux
commit_sha: null
local: required
local_evidence: Scope slice passed 115 combined app tests. Metrics passed 37 targeted app/service tests, locale lint, translation export, architecture health and locked Cargo metadata. Combined staticAnalysis and full Roborazzi verification passed in 3m56s with 2217 app tests, zero failures/errors and one pre-existing RTL gallery skip. Remaining slices are pending.
remote_ci: required
remote_ci_evidence: Scope source and translation export at 8b8984573f538362a94edd8faeef32c1fb498b84 passed terminal CI run 37215439123 and translation workflow 37215439090. Later slices still require exact-SHA terminal CI.
device: required
device_evidence: Real API 37 ARM64 16 KiB emulator build and install passed for 9e23800c0. Scope controls and real VPN startup passed; active-path scan launch failed twice with a generic error before scan persistence. Exact lease rejection is not yet observed; device acceptance remains required.
artifact: required
artifact_evidence: Scope and metrics each have 10 reviewed PNG fixtures including light, dark, Arabic RTL and font 2.0. Targeted metrics record and verification passed for all 10; recorded PNGs exactly match reviewed actuals. Fixed fixture timestamps use scoped UTC while production preserves user timezone. Other slices and actual export inspection are pending.
deployment: not_applicable
deployment_evidence: No production deployment or release is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SCOPE | EPC-1791124243600668 | 115 combined app tests; app/service locale lint; 10 reviewed PNG fixtures; independent code review; real API 37 VPN startup and scope controls passed, but active-path scan start failed with an unclassified error | Reopened for typed recovery and device retry |
| REQ-METRICS | EPC-1791124244103077 | 37 app/service tests cover source/window/freshness, empty and measured-zero data, loss-only data, real percentiles, historical scope and repeated snapshot publication; locale lint, 10 reviewed PNG fixtures, combined staticAnalysis and full Roborazzi verification passed; independent review has no remaining findings | Passed locally |
| REQ-CONFIG | EPC-1791124244595215 | Pending applied-snapshot and lifecycle/recovery tests | Required |
| REQ-SEARCH | EPC-1791124245093725 | Pending picker filtering/selection/dismiss tests | Required |
| REQ-EXPORT | EPC-1791124245588453 | Pending cancel/failure and actual output redaction checks | Required |
| REQ-PAUSE | EPC-1791124246073345 | Pending persisted intent, stale callback and reconstruction tests | Required |
| REQ-PROFILES | EPC-1791124246556219 | Pending actual probe selection, race and local metadata tests | Required |
| REQ-ACCESSIBILITY | EPC-1791124243600668 | All slices require all-locale lint, large-font/RTL/light/dark visual review and semantic controls | Required |
