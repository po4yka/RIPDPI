---
task_id: RLY-1794300111377171
change: repair-apps-script-relay
commit_sha: null
local: required
local_evidence: Regression tests and combined gates pending.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI execution is outside local acceptance; no hosted run requested.
device: required
device_evidence: Public CA provisioning acceptance pending.
artifact: required
artifact_evidence: Affected Kotlin compilation and resource validation pending.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RLY15-AUTH | RLY-1794300154444303 | Pending local regression and review | pending |
| REQ-RLY15-BOUNDARIES | RLY-1794300155126413 | Pending local regression and review | pending |
| REQ-RLY15-HTTP | RLY-1794300155126413 | Pending local regression and review | pending |
| REQ-RLY15-TRUST | RLY-1794300156159325 | Pending local regression and review | pending |
| REQ-RLY15-CONTROL | RLY-1794300156159325 | Pending local regression and review | pending |
| REQ-RLY15-PEER | RLY-1794300157206276 | Pending local regression and review | pending |
| REQ-SEPARATE-CORS-SITES-WITH-PUBLIC-SUFFIX-LIST-BEHAVIOR | RLY-1794300155126413 | Pending fail-closed HTTP test | pending |
| REQ-SEPARATE-CORS-SITES-WITH-PUBLIC-SUFFIX-LIST-COMPAT | RLY-1794300155126413 | Pending independent review | pending |
