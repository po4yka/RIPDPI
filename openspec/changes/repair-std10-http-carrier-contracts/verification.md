---
task_id: TRN-1794211839090466
change: repair-std10-http-carrier-contracts
commit_sha: null
local: required
local_evidence: Pending affected tests, review and source gates.
remote_ci: not_applicable
remote_ci_evidence: This source repair owns local gates; remote runs are reported separately after push.
device: not_applicable
device_evidence: No Android service or device route change; physical acceptance remains outside this repair.
artifact: not_applicable
artifact_evidence: No release or APK delivery is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-STD10-CANCEL | TRN-1794211922684114 | xHTTP sibling cancellation regression and crate tests | pending |
| REQ-STD10-SETTINGS | TRN-1794211923197752 | Raw H2/H3 peer capability tests | pending |
| REQ-STD10-SUCCESS | TRN-1794211923197752 | H2 successful status tunnel exchange tests | pending |
| REQ-STD10-CAPSULE | TRN-1794211923739038 | Malformed, oversized and truncated capsule regressions | pending |
| REQ-STD10-HEADERS | TRN-1794211923739038 | Forbidden response header regressions | pending |
| REQ-STD10-DOCS | TRN-1794211922684114 | Compare documentation to current runtime dispatch | pending |
