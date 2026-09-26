---
task_id: DGN-1790430642039687
change: dgn-1790430642039687-correct-quic-stun-reachability-probes
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: not_applicable
artifact_evidence: No distributable artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-QUIC-VALID-INITIAL | DGN-1790430818948716 | Pending focused packet-selection test and module gate. | required |
| REQ-QUIC-BUILD-FAILURE | DGN-1790430818948716 | Pending native-failure test and module gate. | required |
| REQ-STUN-RESPONSE-MATCH | DGN-1790430824485948 | Pending valid and invalid UDP response tests and module gate. | required |
