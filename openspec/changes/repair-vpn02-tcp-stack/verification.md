---
task_id: VPN-1794140727483999
change: repair-vpn02-tcp-stack
commit_sha: null
local: required
local_evidence: Pending regression tests, Rust quality checks and source gates.
remote_ci: not_applicable
remote_ci_evidence: No hosted CI run is required or manually launched by this native repair task.
device: not_applicable
device_evidence: Native host regressions are the acceptance scope. Android transport acceptance remains separate and is not claimed.
artifact: not_applicable
artifact_evidence: No release binary or golden fixture is changed.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPN02-HALF-CLOSE | VPN-1794140849743430 | Pending both half-close orders, 17-byte and large tails, duplicate FIN and clean resource retirement. | pending |
| REQ-VPN02-IDLE-CLEANUP | VPN-1794140852477934 | Pending idle expiry after EOF with no next application packet. | pending |
| REQ-VPN02-INTERFACE-ENTROPY | VPN-1794140854875280 | Pending repeated interface wire ISNs and entropy error checks. | pending |
| REQ-VPN02-BUFFER-POLICY | VPN-1794140858454513 | Pending native capacity, receive window and backpressure tests. | pending |
| REQ-VPN02-IPV6-MSS | VPN-1794140863320088 | Pending IPv4/IPv6 absent, zero and explicit MSS tests. | pending |

Combined validation step: VPN-1794140869499117. Independent review must precede each repair commit. The final exact SHA and commands will be recorded after integration validation.
