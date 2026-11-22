---
task_id: DGN-1795322193627833
change: verified-diagnostic-upload
commit_sha: null
local: passed
local_evidence: 113 targeted JVM tests and 571 Rust tests passed. Two existing Rust tests are ignored by default. The generated catalog check, Detekt, verifyAppEngineBoundary, and just ci-gates passed.
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: JVM tests cover Android scan admission. A trusted loopback TLS server exercises the native transfer.
artifact: not_applicable
artifact_evidence: The profile uses the ordinary Android app build.
deployment: not_applicable
deployment_evidence: The user provides the control endpoint.
---

# Verification

| Requirement | Execution step | Evidence | Result |
| --- | --- | --- | --- |
| REQ-DGN-1795322193627833-001 | DGN-1795322429286781 | JVM tests cover user input, manual admission, selected paths, and scope loss. Native tests reject missing, invalid, and repeated upload targets. | Passed |
| REQ-DGN-1795322193627833-002 | DGN-1795322429780835 | A trusted local HTTPS server verifies one POST of up to 1 MiB. Tests cover a complete receipt, wrong size and hash, a stall, early EOF, an oversized or malformed receipt, and a redirect. | Passed |
