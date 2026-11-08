---
task_id: VPN-1794152738556618
change: vpn08-flow-owner-attribution
commit_sha: null
local: required
local_evidence: Targeted Rust and Kotlin tests, staticAnalysis, ci-gates, architecture health and locked metadata are pending.
remote_ci: required
remote_ci_evidence: Check exact pushed commits after integration.
device: blocked
device_evidence: Physical two-UID probes and rooted kernel fixtures need separate authorization; no such run is claimed.
artifact: required
artifact_evidence: Affected native and Kotlin contract build is pending.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPN08-UDP-AGE | VPN-1794152941836461 | 18 owning-crate tests passed; baseline regression failed on stale generation at 5 s. F1 commit 1dcb5dc1c21aedd7f16ae92fdd558651c67c9cad. Independent review passed. | Passed (host) |
| REQ-VPN08-START | VPN-1794152942868249 | 59 engine tests passed; baseline first-flow and registration-failure regressions failed as expected. F4 commit 5e9965770eb2bd4bee8c5c901669797082f3f526. Independent review passed. | Passed (host) |
| REQ-VPN08-SESSION | VPN-1794152943919544 | Baseline 19 tests / 1 failed at retired counter assertion; candidate 42 service tests / 0 failures or skips. F3 commit e36dbf9f7a52bb66fe907c04363c0126d9d6afed. Independent review passed after test delegation repair. | Passed (host) |
| REQ-VPN08-DESTINATION | VPN-1794152944925858 | Native learning scope regressions: baseline 3 failed/1 passed, then 92 owning-crate tests passed. Transport integration is pending. | Partial (host) |
| REQ-VPN08-UNKNOWN | VPN-1794152944925858 | Unknown and bounded slow resolver paths | Pending |
| REQ-VPN08-PRIVACY | VPN-1794152944925858 | Opaque scope serialization and transient Kotlin identity tests | Pending |
| REQ-VPN08-MAPDNS | VPN-1794152945958197 | Synthetic TCP/UDP identity regressions | Pending |

## Review and combined gates

VPN-1794152947200481 owns independent correctness, JNI and async review and combined-tree validation. Host evidence does not prove Android kernel ownership. No baselines are blessed by this task.

Lower scope contract tests passed before transport integration: runtime-policy 92, Android telemetry adapter 25 (including existing goldens), runtime services 5. App scope is an additive optional field within schema 3; no fixture was blessed. Source patch handed to the scope writer for the combined F2 commit.
