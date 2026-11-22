---
task_id: DGN-1795345317475114
change: latest-completed-diagnostics-export
commit_sha: null
local: passed
local_evidence: 226 targeted JVM tests passed, including 19 Room tests and 12 app tests. Detekt and the diagnostics lint checks passed.
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: JVM archive tests read ZIP files. A Robolectric Room test exercises the Android database query.
artifact: not_applicable
artifact_evidence: The change uses the standard application build.
deployment: not_applicable
deployment_evidence: Export is a local operation.
---

# Verification

| Requirement | Execution step | Evidence | Result |
| --- | --- | --- | --- |
| REQ-DGN-1795345317475114-001 | DGN-1795345346031271 | Two archive tests failed on the original behavior. The passing tests verify automatic and explicit requests, archive completeness, saved probe rows, and an oversized report behind 51 running scans in Room. | Passed |
