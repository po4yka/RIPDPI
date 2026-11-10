---
task_id: TRN-1794335331879799
change: trn01-vless-input-validation
commit_sha: null
local: required
local_evidence: Pending regression tests, affected-consumer gates and independent reviews.
remote_ci: not_applicable
remote_ci_evidence: This change uses local source and host test gates. Hosted acceptance is not requested or claimed; pushing main does not prove hosted CI.
device: not_applicable
device_evidence: Parser and origin admission changes use host regressions and loopback. Physical VPN and external-network acceptance are not claimed.
artifact: not_applicable
artifact_evidence: No release artifact is produced or accepted by this task.
deployment: not_applicable
deployment_evidence: No deployment is owned by this task.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-ADDONS | TRN-1794335473975866 | Pending red/green tests and independent pinned Xray vectors. | required |
| REQ-PARTIAL | TRN-1794335473975866 | Pending partial-I/O, EOF and cancellation regressions. | required |
| REQ-ORIGIN-FLOW | TRN-1794335474552676 | Pending real loopback no-connect and valid relay regressions. | required |
| REQ-EAGER-CANCEL | TRN-1794335475069126 | Pending API contract documentation and combined checks. | required |
