---
task_id: DGN-1795320365568134
change: verified-diagnostic-download
commit_sha: null
local: passed
local_evidence: 93 targeted JVM tests and 480 Rust tests passed. Two existing Rust tests are ignored by default. Detekt, verifyAppEngineBoundary, and just ci-gates passed.
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: The form and scan admission use JVM tests. The transfer uses a trusted local TLS peer.
artifact: not_applicable
artifact_evidence: The profile uses the ordinary Android app build.
deployment: not_applicable
deployment_evidence: The user provides a control endpoint.
---

# Verification

| Requirement | Execution step | Evidence | Result |
| --- | --- | --- | --- |
| REQ-DGN-1795320365568134-001 | DGN-1795320883033753 | JVM tests cover manual controls, scan admission, and scope loss. Rust tests cover native request validation. | Passed |
| REQ-DGN-1795320365568134-002 | DGN-1795320884248050 | Trusted loopback TLS tests cover complete fixed-length and chunked bodies, a stall after headers, early EOF, a wrong digest, and excess data. The reader test verifies the exact plaintext limit. | Passed |
