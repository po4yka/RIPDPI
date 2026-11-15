---
task_id: DGN-1794745065716076
change: repair-dia15-dpi-suite
commit_sha: null
local: required
local_evidence: Run owning regression tests and combined source gates before integration.
remote_ci: not_applicable
remote_ci_evidence: Report remote CI separately from local acceptance.
device: required
device_evidence: Device checks for routing and Android JNI remain pending.
artifact: not_applicable
artifact_evidence: No release artifact is required for this source repair.
deployment: not_applicable
deployment_evidence: No deployment is owned by the repair.
---

# Verification

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DIA15-TCP | DGN-1794745216921361 | Loopback HEAD and admission tests | required |
| REQ-DIA15-SNI | DGN-1794745216921361 | Matched TLS baseline and candidate tests | required |
| REQ-DIA15-TRANSFER | DGN-1794745217875425 | HTTP byte and cancellation tests, DC dispatcher tests | required |
| REQ-DIA15-ECH | DGN-1794745218960894 | Native and Kotlin ECH contract tests | required |
| REQ-DIA15-SUITE | DGN-1794745219809954 | Scheduler, aggregate, and UI tests | required |
| Combined source gates | DGN-1794745220607665 | Independent review, architecture and source gates | required |
