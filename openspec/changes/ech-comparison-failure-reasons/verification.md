---
task_id: DGN-1795351267598237
change: ech-comparison-failure-reasons
commit_sha: null
local: passed
local_evidence: 406 Rust tests and 82 focused Android JVM tests passed. The source gates and independent review passed.
remote_ci: required
remote_ci_evidence: Required on the pull request.
device: not_applicable
device_evidence: The comparison uses controlled DNS and loopback TLS fixtures and Android JVM tests.
artifact: not_applicable
artifact_evidence: The change uses the standard application build.
deployment: not_applicable
deployment_evidence: The change uses the existing probe routes and endpoint.
---

# Verification

| Requirement | Execution step | Evidence | Result |
| --- | --- | --- | --- |
| REQ-DGN-1795351267598237-001 | DGN-1795351483544511 | DNS evidence and loopback TLS fixtures, comparison runner, history and archive test, and app presentation test | passed |
| REQ-DGN-1795351267598237-002 | DGN-1795351484602117 | HTTPS record regression with matching and mismatched ports | passed |
