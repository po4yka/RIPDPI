---
task_id: ENG-1794637889757963
change: fix-dpi17-audit-findings
commit_sha: null
local: required
local_evidence: Owning unit tests, enabled wire regression, locale lint and source gates are required before integration.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI is not an acceptance gate owned by this local repair; report any observed run separately.
device: not_applicable
device_evidence: No device or root behavior changes; no physical acceptance requested.
artifact: not_applicable
artifact_evidence: No APK or release artifact requested.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DUMMY-COUNT | ENG-1794638159294108 | Kotlin and native boundary and planner regressions | Required |
| REQ-DUMMY-BYTES | ENG-1794638159294108 | Oversized host and packet bound regressions | Required |
| REQ-DUMMY-COMPAT | ENG-1794638159294108 | Existing and extended planner tests | Required |
| REQ-DUMMY-UI | ENG-1794638174316919 | Locale lint and translation export | Required |
| REQ-DUMMY-WIRE | ENG-1794638179707591 | Enabled production loopback regression and oracle | Required |
