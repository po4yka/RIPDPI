---
task_id: VPN-1794196711383747
change: repair-root-helper-ipc
commit_sha: 7b475fb9e1b433c80daee34db6cd2ec6b77f23a3
local: required
local_evidence: Regressions and combined source gates have not run for this repair.
remote_ci: required
remote_ci_evidence: No remote run for this change yet.
device: blocked
device_evidence: Root activation and physical or external probes require separate authorization; no device acceptance is claimed.
artifact: required
artifact_evidence: The repaired helper must compile for Android ARM64.
deployment: not_applicable
deployment_evidence: This repair does not deploy a service.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-IPC-CLOEXEC | VPN-1794196899260030 | Kernel flags, working socket, exec and error regressions are pending. | required |
| REQ-IPC-LIMITS | VPN-1794196899730212 | Reproduction, bounded allocation, coverage and handler liveness are pending. | required |
| REQ-IPC-DEADLINE | VPN-1794196900171780 | Late reader and trickle response regressions are pending. | required |
| REQ-IPC-SOCKET | VPN-1794196900171780 | Invalid fd type and cleanup regressions are pending. | required |
| REQ-IPC-COMPAT | VPN-1794196900616146 | Current F3/F4 provenance verified at 6808d8e91; current tests, combined gates and review are pending. | required |

## Acceptance scope

Implement and deliver the local fixes with distinct finding commits. Preserve the existing private-directory trust model. Unconfirmed filesystem attacks and active-VPN raw-route behavior remain explicit audit uncertainties, not invented accepted behavior. Keep this task in review while remote or device evidence is outstanding.
