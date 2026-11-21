---
task_id: VPN-1795264616970883
change: confirm-selector-vpn-recovery
commit_sha: null
local: passed
local_evidence: 285 focused JVM tests passed. Detekt for app and service, the app engine boundary, source gates, and task validation passed.
remote_ci: required
remote_ci_evidence: null
device: passed
device_evidence: On the Android API 35 arm64 emulator, the owned peer stalled the old VPN path. The service applied the reserve and a separate UID downloaded 262144 bytes with the expected SHA-256. The selector confirmed a complete control response on the new route.
artifact: not_applicable
artifact_evidence: This change uses the ordinary Android build.
deployment: not_applicable
deployment_evidence: This change runs in the client.
---

# Verification

Five tests failed on the previous selector. The final JVM suite covers missing application proof, late rejection, timeout, supersession, and the distinction between a stalled body and a verified transfer.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
| --- | --- | --- | --- |
| REQ-VPN-1795264616970883-001 | VPN-1795264727190744 | Selector reload and retained runtime cleanup regressions | Passed |
| REQ-VPN-1795264616970883-002 | VPN-1795264728181527 | Applied VPN payload regressions and Android API 35 owned-peer test | Passed |
