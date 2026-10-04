---
task_id: EPC-1791124000119505
change: measured-network-ux
commit_sha: null
local: required
local_evidence: Scope slice passed 115 combined app tests; typed rejection correction passed 133 targeted tests and staticAnalysis. Original metrics passed 37 targeted app/service tests and combined staticAnalysis/full Roborazzi verification with 2217 app tests, zero failures/errors and one pre-existing RTL gallery skip. Final Home correction passed 40 targeted app/service tests and full staticAnalysis, including real text/hit-target bounds and both CTA clicks at font 2.0 in LTR/RTL. Both locale lint gates and translation export passed; strict architecture health passed after the owning generator removed only the obsolete HomeScreen suppression entry. Owning Home PNG record and verify each passed all 16 tests without failure/error/skip. Real-device Home acceptance and exact-SHA terminal CI passed at 5c300cf763e6fd44853cdc6241171787628e8583. The measured-metrics step is complete; the other six feature steps remain open.
remote_ci: required
remote_ci_evidence: Scope source and translation export at 8b8984573f538362a94edd8faeef32c1fb498b84 passed terminal CI run 37215439123 and translation workflow 37215439090. Typed active-path rejection at 284ec8e8bfc0bcaf842c88addba287634dc86866 passed terminal full CI 37222099667. Bounded request receipts at 11705622ba420a295b4f92ca49b7e787d6546be9 passed terminal full CI 37225135280, including all Android API jobs and release verification. Metrics run 37219836452 had a failed API35 Xray negative TUN test with direct HTTP receipts expected 1 and actual 2; its source causality is not established and the overall run was cancelled after the next push. Home run 37228242087 failed in Simple maximum-font PNG namespace and transient Compose-root polling. Fixes 210d3983f64f713497c07e9ecb7d3a6f1374b36a and 5c300cf763e6fd44853cdc6241171787628e8583 passed exact-SHA terminal CI run 37232667135, including Roborazzi, static analysis, all release variants and instrumentation on API 27, 33, 35, 36 and 37. Later slices still require exact-SHA terminal outcomes.
device: required
device_evidence: Real API37 ARM64 16KiB build and install passed for 284ec8e8b. A single corrective active-path attempt failed before scan persistence with RouteEvidenceUnavailable, BridgeReady, callback TimedOut and owner/consistency Unavailable. The platform has an owned VPN and registered observer; callback convergence remains under diagnosis. VPN continued running. That build also exposed unsupported Home zeros and nominal quality with no RTT samples. Corrected Home is installed from exact commit 5c300cf763e6fd44853cdc6241171787628e8583: the real screen shows 2 RTT samples, 285 ms RTT p50 and 3 ms jitter, an explicit insufficient-samples assessment, source Proxy and current snapshot. Root inspected the complete screenshot without clipping; crash buffer is empty. Two accessibility dumps returned a null root, so device evidence does not claim semantic automation success. Debug APK SHA256 is 13d869c9e910690ca3b857a35abd7f81f8ed9bd372db68da18b2239f9919257f.
artifact: required
artifact_evidence: Scope and original metrics each have 10 reviewed PNG fixtures including light, dark, Arabic RTL and font 2.0. Home correction has 16 reviewed fixtures including absent, partial, measured-zero and stopped values plus all affected Home/NavRail layouts. Isolated owning record and verify passed all 16; their PNG hashes exactly match every reviewed actual, and source hashes were unchanged. Large-font/RTL text and CTA clipping was fixed before recording. Fixed fixture timestamps use scoped UTC while production preserves user timezone. Four whole-Home fixtures now use Full/Simple namespaces. Owning record/verify and combined-tree verification passed 8 tests per variant, with byte-identical Full renames, reviewed Simple actuals and all 335 other PNGs unchanged; this was committed and pushed as 210d3983f64f713497c07e9ecb7d3a6f1374b36a. Other slices and actual export inspection are pending.
deployment: not_applicable
deployment_evidence: No production deployment or release is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SCOPE | EPC-1791124243600668 | 115 combined app tests and 10 reviewed PNGs; typed rejection correction passed 133 tests, staticAnalysis and independent review. Real API37 reports RouteEvidenceUnavailable with callback TimedOut while the owned VPN remains running | Reopened for callback convergence and positive device acceptance |
| REQ-METRICS | EPC-1791124244103077 | Original metrics tests/PNGs passed; real Home exposed unsupported zeros and nominal quality with zero samples. Final Home correction passed 40 targeted tests, full staticAnalysis, locale/export gates, independent source review, strict architecture health and all 16 reviewed/recorded/verified Home PNGs. Real partial-measurement Home inspection passed at 5c300cf and exact-SHA CI run 37232667135 completed successfully | Complete with observed device, visual and terminal CI evidence |
| REQ-CONFIG | EPC-1791124244595215 | Pending applied-snapshot and lifecycle/recovery tests | Required |
| REQ-SEARCH | EPC-1791124245093725 | Pending picker filtering/selection/dismiss tests | Required |
| REQ-EXPORT | EPC-1791124245588453 | Pending cancel/failure and actual output redaction checks | Required |
| REQ-PAUSE | EPC-1791124246073345 | Pending persisted intent, stale callback and reconstruction tests | Required |
| REQ-PROFILES | EPC-1791124246556219 | Pending actual probe selection, race and local metadata tests | Required |
| REQ-ACCESSIBILITY | EPC-1791124243600668 | All slices require all-locale lint, large-font/RTL/light/dark visual review and semantic controls | Required |
