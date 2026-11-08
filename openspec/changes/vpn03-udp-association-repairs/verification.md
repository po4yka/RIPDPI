---
task_id: VPN-1794141873537976
change: vpn03-udp-association-repairs
commit_sha: null
local: required
local_evidence: Pending regression and combined gate results.
remote_ci: required
remote_ci_evidence: Pending push of the integrated candidate.
device: not_applicable
device_evidence: This repair task uses host and loopback validation. Physical device and external-network acceptance remain unverified.
artifact: not_applicable
artifact_evidence: No release artifact is published by this change. Patched Xray source tests do not prove the shipped AAR.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPN03-XRAY-AUTHORITY | VPN-1794142081038544 | Managed Xray host/pinned authority tests and Kotlin projection tests | Pending |
| REQ-VPN03-REPLY-OWNERSHIP | VPN-1794142081837995 | Capacity and reverse reply regression tests | Pending |
| REQ-VPN03-LEASE-LIFETIME | VPN-1794142082448229 | Target idle expiry, shared pin ownership, late event rejection | Pending |
| REQ-VPN03-COMPLETE-DATAGRAM | VPN-1794142083191753 | Literal frames, independent IPv4/IPv6 reassembly/checksum tests | Pending |
| REQ-VPN03-CHECKSUM | VPN-1794142083879816 | Independent valid/invalid checksum vectors and routing rejection | Pending |
| REQ-VPN03-REPLY-OWNERSHIP | VPN-1794142084413873 | Hypothesis checks, combined crate gates, independent review, source gates | Pending |
