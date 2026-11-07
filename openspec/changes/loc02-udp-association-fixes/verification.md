---
task_id: PRX-1794076309764211
change: loc02-udp-association-fixes
commit_sha: null
local: required
local_evidence: Pending regression tests, source gates and independent review.
remote_ci: not_applicable
remote_ci_evidence: This change owns local verification and a requested main push; no hosted CI acceptance is claimed.
device: not_applicable
device_evidence: No device, root or external server activation is in scope; local loopback covers these regressions.
artifact: not_applicable
artifact_evidence: No release artifact is produced by this repair task.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UDP-SETUP-CANCEL | PRX-1794076380668103 | Pending owning regression tests and combined source gates. | pending |
| REQ-UDP-CONTROL-LIFETIME | PRX-1794076381212455 | Pending owning regression tests and combined source gates. | pending |
| REQ-UDP-REPLY-AUTHORITY | PRX-1794076381693446 | Pending owning regression tests and combined source gates. | pending |
| REQ-UDP-UDP-GATE | PRX-1794076382119939 | Pending owning regression tests and combined source gates. | pending |
| REQ-UDP-DATAGRAM-SIZE | PRX-1794076382558326 | Pending owning regression tests and combined source gates. | pending |
| REQ-UDP-SOURCE-STABILITY | PRX-1794076382995792 | Pending owning regression tests and combined source gates. | pending |
