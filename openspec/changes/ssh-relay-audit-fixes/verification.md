---
task_id: RLY-1794300186609700
change: ssh-relay-audit-fixes
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
| REQ-SSH-REKEY-PIN | RLY-1794300262750090 | Same-key and changed-key independent-peer regression | Required |
| REQ-SSH-IMPORT-TRUST | RLY-1794300278412406 | Admission and import/editor acceptance and dismissal regressions | Required |
| REQ-SSH-PASSPHRASE | RLY-1794300282442646 | Actual share mapper and URI round trip with a whitespace passphrase | Required |
| REQ-SSH-DISCOVERY-FALLBACK | RLY-1794300285482245 | Stalled first address, successful later address, and revocation tests | Required |
| REQ-SSH-CLOSE-BACKPRESSURE | RLY-1794300288104643 | Retained unread channel and bounded close independent-peer regression | Required |

## Combined validation

RLY-1794300290056513 owns independent review, targeted suites, just ci-gates, architecture health, locked Cargo metadata, and exact-SHA evidence before integration. Device and artifact acceptance must be recorded as observed or explicitly unavailable, never inferred from host tests. No production action is required.
