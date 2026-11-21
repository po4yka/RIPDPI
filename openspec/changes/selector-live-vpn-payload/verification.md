---
task_id: VPN-1795255851577459
change: selector-live-vpn-payload
commit_sha: null
local: passed
local_evidence: 221 selector, selection store, and TLS fixture JVM tests passed. Static analysis, source gates, three Go fixture tests, 20 Python runner and fixture tests, and workflow lint passed.
remote_ci: required
remote_ci_evidence: null
device: passed
device_evidence: Android API 35 arm64 emulator with the owned peer. The applied VPN received a partial response. A fresh primary delivered the full body. After automatic reload, a distinct UID read 262144 bytes with the expected SHA-256 through the reserve.
artifact: not_applicable
artifact_evidence: No distributable artifact is required.
deployment: not_applicable
deployment_evidence: No deployment is part of this change.
---

# Verification

On Android API 35, the previous selector timed out after 180 seconds waiting for a reserve. The owned peer recorded eight complete control responses and received no request marked for a stalled body. The new selector reached the active route, recorded partial responses there, and switched to a verified reserve.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
| --- | --- | --- | --- |
| REQ-VPN-1795255851577459-001 | VPN-1795255882663118 | API 35 owned fixture, partial response receipts and full-body transfer through the applied reserve | Passed |
| REQ-VPN-1795255851577459-002 | VPN-1795255881240958 | Selection store, scope and stale route JVM regressions | Passed |
