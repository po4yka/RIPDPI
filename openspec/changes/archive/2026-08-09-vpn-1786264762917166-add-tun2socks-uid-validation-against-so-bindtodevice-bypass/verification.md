---
task_id: VPN-1786264762917166
change: vpn-1786264762917166-add-tun2socks-uid-validation-against-so-bindtodevice-bypass
commit_sha: 4d852cb56c2ba92f27a75c902ebeccdb1784fc30
local: passed
local_evidence: Current TUN UID policy, TCP/UDP/ICMP/MapDNS admission, capability eligibility, JNI, and evidence-validator suites pass; source commits are recorded in the portfolio work log.
remote_ci: passed
remote_ci_evidence: Privileged SO_BINDTODEVICE run 29652023020 passed all 12 IPv4/IPv6 TCP/UDP phases; full CI run 31295121189 passed on a descendant containing the implementation.
device: passed
device_evidence: Pixel 7 API 37 kernel 6.1 run at 2195272b78a08493adb09a7df90b270b6fafdefe proved allowed traffic, excluded-UID denial, zero denied delivery, packet-path telemetry, and liveness; manifest SHA-256 cb4e9c48c6bce79f3f072ae8b96cb936ab6b551529cdaa2c3ed105e5cb2278d2.
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this portfolio area.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPN-1786264762917166-001 | VPN-1786264762917376 | Source audit and current workspace | passed |
| REQ-VPN-1786264762917166-002 | VPN-1786264762917494 | TUN/JNI policy tests and current source | passed |
| REQ-VPN-1786264762917166-003 | VPN-1786264762917458 | Run 29652023020 and Pixel manifest cb4e9c48 | passed |
| REQ-VPN-1786264762917166-004 | VPN-1786264762917653 | Zero-delivery, RST, counters, and liveness evidence | passed |
| REQ-VPN-1786264762917166-005 | VPN-1786266573979046 | TCP/UDP/ICMP/MapDNS focused suites | passed |
| REQ-VPN-1786264762917166-006 | VPN-1786266573979750 | Capability eligibility tests and fail-closed fallback | passed |
