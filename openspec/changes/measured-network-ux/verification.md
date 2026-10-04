---
task_id: EPC-1791124000119505
change: measured-network-ux
commit_sha: null
local: required
local_evidence: Scope slice passed 115 combined app tests; typed rejection correction passed 133 targeted tests and staticAnalysis. Metrics passed 37 targeted app/service tests, locale lint, translation export, architecture health and locked Cargo metadata. Combined staticAnalysis and full Roborazzi verification passed in 3m56s with 2217 app tests, zero failures/errors and one pre-existing RTL gallery skip. Real Home no-sample projection requires correction. Remaining slices are pending.
remote_ci: required
remote_ci_evidence: Scope source and translation export at 8b8984573f538362a94edd8faeef32c1fb498b84 passed terminal CI run 37215439123 and translation workflow 37215439090. Metrics run 37219836452 had a failed API35 Xray negative TUN test with direct HTTP receipts expected 1 and actual 2; its source causality is not established and the overall run was cancelled after the next push. Later slices still require exact-SHA terminal CI.
device: required
device_evidence: Real API37 ARM64 16KiB build and install passed for 284ec8e8b. A single corrective active-path attempt failed before scan persistence with RouteEvidenceUnavailable, BridgeReady, callback TimedOut and owner/consistency Unavailable. The platform has an owned VPN and registered observer; callback convergence remains under diagnosis. VPN continued running. Home showed nominal quality and zero RTT/jitter with zero samples, requiring a metrics correction.
artifact: required
artifact_evidence: Scope and metrics each have 10 reviewed PNG fixtures including light, dark, Arabic RTL and font 2.0. Targeted metrics record and verification passed for all 10; recorded PNGs exactly match reviewed actuals. Fixed fixture timestamps use scoped UTC while production preserves user timezone. Other slices and actual export inspection are pending.
deployment: not_applicable
deployment_evidence: No production deployment or release is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SCOPE | EPC-1791124243600668 | 115 combined app tests and 10 reviewed PNGs; typed rejection correction passed 133 tests, staticAnalysis and independent review. Real API37 reports RouteEvidenceUnavailable with callback TimedOut while the owned VPN remains running | Reopened for callback convergence and positive device acceptance |
| REQ-METRICS | EPC-1791124244103077 | 37 app/service tests, 10 reviewed PNG fixtures, combined staticAnalysis and full Roborazzi verification passed. Real Home inspection exposed unsupported RTT/jitter zeros and nominal quality with zero samples | Reopened for Home absent/partial measurement projection |
| REQ-CONFIG | EPC-1791124244595215 | Pending applied-snapshot and lifecycle/recovery tests | Required |
| REQ-SEARCH | EPC-1791124245093725 | Pending picker filtering/selection/dismiss tests | Required |
| REQ-EXPORT | EPC-1791124245588453 | Pending cancel/failure and actual output redaction checks | Required |
| REQ-PAUSE | EPC-1791124246073345 | Pending persisted intent, stale callback and reconstruction tests | Required |
| REQ-PROFILES | EPC-1791124246556219 | Pending actual probe selection, race and local metadata tests | Required |
| REQ-ACCESSIBILITY | EPC-1791124243600668 | All slices require all-locale lint, large-font/RTL/light/dark visual review and semantic controls | Required |
