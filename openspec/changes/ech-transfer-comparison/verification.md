---
task_id: DGN-1795331701582623
change: ech-transfer-comparison
commit_sha: null
local: passed
local_evidence: 648 targeted Rust tests passed, with two pre-existing ignored tests. 109 targeted Android JVM tests passed.
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: JVM tests exercise the manual entry and scope handling. Trusted local TLS peers exercise the protocol and transfer.
artifact: not_applicable
artifact_evidence: The profile uses the ordinary Android app build.
deployment: not_applicable
deployment_evidence: The user supplies the control origin.
---

# Verification

| Requirement | Execution step | Evidence | Result |
| --- | --- | --- | --- |
| REQ-DGN-1795331701582623-001 | DGN-1795332095947018 | Android entry and native admission tests cover the fixed address, manual profile, limits, and three-probe budget. Local TLS tests verify the three modes with HTTPS, reject unaccepted ECH, and check the DNS owner for a non-default port. | Passed |
| REQ-DGN-1795331701582623-002 | DGN-1795332096467008 | Runner tests cover a verified body and a body stall at the same fixed address, preserve partial bytes, and check cancellation. JVM tests cover presentation and report invalidation after a network change. | Passed |
