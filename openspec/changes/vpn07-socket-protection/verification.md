---
task_id: VPN-1794152756242597
change: vpn07-socket-protection
commit_sha: null
local: required
local_evidence: Targeted regression tests, affected package checks, independent review and combined source gates are pending.
remote_ci: required
remote_ci_evidence: No exact-source remote CI run has been observed.
device: required
device_evidence: Non-root Android protection acceptance has not run. Physical and external-server probes are outside this authorization.
artifact: required
artifact_evidence: Affected native Android compilation and test artifact evidence are pending.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPN07-PROXY | VPN-1794152886426471 | Converter and kernel denial regressions | Pending |
| REQ-VPN07-UDS-BOUNDS | VPN-1794152888395240 | Full accept-queue timeout and fd lifetime regressions | Pending |
| REQ-VPN07-CLEANUP | VPN-1794152890137023 | Stop failure, pending cleanup and stale owner regressions | Pending |
| REQ-VPN07-PROFILE-CHECK | VPN-1794152891543030 | Active, inactive and stale protection lease checks | Pending |
| REQ-VPN07-UDP-ORDER | VPN-1794152892651101 | Kernel getsockname-before-protect and rejection checks | Pending |
| REQ-VPN07-SERVER-BOUNDS | VPN-1794152894507850 | Stalled handshake recovery, backpressure and descriptor error closure | Pending |

## Integration

Base source: `40e8c4c66f21fdddcd91bf2629fb7d562c113da8`. Each confirmed finding is committed separately after independent review. Main integration and origin push are explicitly authorized by the user. Required evidence remains open until observed; host tests never stand in for Android routing.
