---
task_id: SVC-1791566464861507
change: svc-1791566464861507-acceptance-physical-handover
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: required
artifact_evidence: null
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-HANDOVER-PHYSICAL | SVC-1791566704596382 | Original event changed-field evidence and handover comparison regression tests | required |
| REQ-HANDOVER-BASELINE | SVC-1791566703809797 | Actual binder/authority/snapshot/provider regression: 2 failures at final no-handover assertion after physical equality and token presence checks pass; original historical event field remains inferred | reproduced; final acceptance required |
| REQ-HANDOVER-COMPATIBILITY | SVC-1791566704596382 | Physical state and valid generation change tests; service suite and lint | required |
| REQ-HANDOVER-RUNTIME | SVC-1791566705394380 | Genuine clean Android profile, full Xray repeat, VM-routed Xray, report verification, and exact published CI | required |

The c25730eca failed run and successful Linux reports remain separate from later diagnostic and corrected runs. No required category is accepted at planning time.
