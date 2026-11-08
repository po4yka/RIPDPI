---
task_id: PRX-1794125405826480
change: loc07-transparent-contracts
commit_sha: null
local: required
local_evidence: All four source repairs have reviewed regression evidence. Combined gates on the rebased source are pending.
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
| REQ-LOC07-IPV6 | PRX-1794125540257589 | Real raw-wire RED/GREEN covers IPv4, mapped IPv4 and IPv6. Author unit suite345, Clippy and fmt passed; independent review approved. | passed |
| REQ-LOC07-SCOPE | PRX-1794125540754730 | Actual Linux RED failed scope0 versus42; GREEN1 passed. General and unsafe review approved; full lib suite67passed3BPF EPERM. Independent C SO_ATTACH_FILTER also returns EPERM in the same sandbox. | passed |
| REQ-LOC07-SELF | PRX-1794125541322538 | Linux12 and macOS10 real listener integration tests passed, with runtime346/adapter74 units and Clippy/fmt. Canonical mapped bind/target review findings are fixed and final staged source approved. | passed |
| REQ-LOC07-CANCEL | PRX-1794125541790164 | Real runtime RED4.02s/forcedabort; final Linux14cancellation/TFO/DNS cases passed. Pending socket stops25to32ms, real refused error stays visible. General and unsafe review approved. | passed |
| REQ-LOC07-PRESERVE | PRX-1794125542204978 | Existing auth, block, bytes, FIN and unsupported-platform tests plus combined gates pending. | required |

## Evidence limits

The initial audit has only partial runtime proof. Successful REDIRECT, Android kernel behavior and external compatibility remain unverified. This change does not install firewall rules or activate root. The Linux backlog reproduction establishes the pending socket condition; runtime reproduction is required before claiming H1 fixed.
