---
task_id: VPN-1794158885036621
change: repair-nfqws2-lifecycle
commit_sha: null
local: required
local_evidence: Regression tests and combined gates are pending.
remote_ci: required
remote_ci_evidence: Hosted CI has not run for the repairs.
device: blocked
device_evidence: Root activation, physical traffic, and external probes require separate authorization; no such evidence is claimed.
artifact: not_applicable
artifact_evidence: This repair does not publish a release artifact.
deployment: not_applicable
deployment_evidence: This repair does not deploy a service.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-NFQ-MONITOR | VPN-1794159017607315 | Dead-helper registration and stale-session regression tests are pending. | required |
| REQ-NFQ-PROCESS | VPN-1794159020495755 | Scoped predecessor shutdown and uncertain ownership tests are pending. | required |
| REQ-NFQ-CHAINS | VPN-1794159023606659 | Foreign UID, delimited name, and proven legacy recovery tests are pending. | required |
| REQ-NFQ-FILE | VPN-1794159025344157 | Plain/compressed Lua and symlink boundary tests are pending. | required |
| REQ-NFQ-REAP | VPN-1794159031763874 | Cleanup deadline, retry, activation, and shutdown tests are pending. | required |
| REQ-NFQ-COMPAT | VPN-1794159033246872 | Existing root/YAML gating tests and combined local gates are pending. | required |

## Acceptance scope

The user authorized local repairs, separate finding commits, main integration, and push. Existing device and external compatibility gaps remain separate. Do not archive while required or blocked evidence remains unresolved.
