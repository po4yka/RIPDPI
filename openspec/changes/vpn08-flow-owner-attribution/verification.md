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
| REQ-VPN08-UDP-AGE | VPN-1794152941836461 | Ordinary UDP virtual-time cache and live routing regressions | Pending |
| REQ-VPN08-START | VPN-1794152942868249 | Engine startup failure and cancellation tests | Pending |
| REQ-VPN08-SESSION | VPN-1794152943919544 | Session replacement and package invalidation race tests | Pending |
| REQ-VPN08-DESTINATION | VPN-1794152944925858 | Same-IP two-app learning and same-app TCP fallback tests | Pending |
| REQ-VPN08-UNKNOWN | VPN-1794152944925858 | Unknown and bounded slow resolver paths | Pending |
| REQ-VPN08-PRIVACY | VPN-1794152944925858 | Opaque scope serialization and transient Kotlin identity tests | Pending |
| REQ-VPN08-MAPDNS | VPN-1794152945958197 | Synthetic TCP/UDP identity regressions | Pending |

## Review and combined gates

VPN-1794152947200481 owns independent correctness, JNI and async review and combined-tree validation. Host evidence does not prove Android kernel ownership. No baselines are blessed by this task.
