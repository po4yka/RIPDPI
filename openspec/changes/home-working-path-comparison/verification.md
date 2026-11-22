---
task_id: DGN-1795338086230261
change: home-working-path-comparison
commit_sha: null
local: passed
local_evidence: 93 targeted JVM tests passed. All three new regressions failed before the fix.
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: The JVM Home workflow fixture uses the scan-controller boundary and an owned route lease.
artifact: not_applicable
artifact_evidence: This change uses the standard application build.
deployment: not_applicable
deployment_evidence: The change uses the existing catalog and server endpoints.
---

# Verification

| Requirement | Execution step | Evidence | Result |
| --- | --- | --- | --- |
| REQ-DGN-1795338086230261-001 | DGN-1795338148578479 | The Home workflow tests a working RAW domain and an HTTP timeout on the active route with an owned proxy lease. The comparison reports a VPN path regression. | Passed |
| REQ-DGN-1795338086230261-002 | DGN-1795338149230827 | The selection tests cover a TLS-only control, a partial RAW report, and reuse of one existing target slot. | Passed |
