---
task_id: PRX-1794125405826480
change: loc07-transparent-contracts
commit_sha: null
local: required
local_evidence: Implementation and exact-source checks are pending.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI is not an acceptance gate for this local repair. Push alone does not prove CI success.
device: not_applicable
device_evidence: No Android API or service composition change is planned. Physical device and root acceptance are not authorized.
artifact: not_applicable
artifact_evidence: No packaged Android or release artifact is published by this repair.
deployment: not_applicable
deployment_evidence: No production deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-LOC07-IPV6 | PRX-1794125540257589 | Red/green raw relay family regression and full runtime unit suite pending. | required |
| REQ-LOC07-SCOPE | PRX-1794125540754730 | Existing Linux fd suite with nonzero scope regression pending. | required |
| REQ-LOC07-SELF | PRX-1794125541322538 | Separate listener, real wildcard/dual-stack and prebound regression pending. | required |
| REQ-LOC07-CANCEL | PRX-1794125541790164 | Full runtime pending connect regression and cancellation gate pending. | required |
| REQ-LOC07-PRESERVE | PRX-1794125542204978 | Existing auth, block, bytes, FIN and unsupported-platform tests plus combined gates pending. | required |

## Evidence limits

The initial audit has only partial runtime proof. Successful REDIRECT, Android kernel behavior and external compatibility remain unverified. This change does not install firewall rules or activate root. The Linux backlog reproduction establishes the pending socket condition; runtime reproduction is required before claiming H1 fixed.
