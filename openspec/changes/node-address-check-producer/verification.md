---
task_id: DGN-1795868945773730
change: node-address-check-producer
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this change.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DGN-1795868945773730-001 | DGN-1795869175570371 | Files vendored byte-for-byte in 3d35aed42f and pinned in vendored-deploy-contracts.sha256 by a6de52b38e (deploy contract/ at 6e2568ce4430); check_vendored_deploy_contracts.py with --source exit 0; deploy ci run 46147749765 on 6e2568ce44 passed | passed |
| REQ-DGN-1795868945773730-002 | DGN-1795869176041476 | Pending | required |
| REQ-DGN-1795868945773730-003 | DGN-1795869176511401 | Pending | required |
| REQ-DGN-1795868945773730-004 | DGN-1795869176997438 | Pending | required |
| REQ-DGN-1795868945773730-005 | DGN-1795869189217727 | Pending | required |
