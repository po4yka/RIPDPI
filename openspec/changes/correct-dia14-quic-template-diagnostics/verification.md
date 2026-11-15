---
task_id: DGN-1794744778962822
change: correct-dia14-quic-template-diagnostics
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: Host loopback and JNI tests cover the specified first-flight behavior. Physical routes and browser compatibility are outside this change.
artifact: not_applicable
artifact_evidence: No binary publication is part of this repair.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-QUIC-TEMPLATE-ATTRIBUTION | DGN-1794744877442856 | Kotlin probe and suite aggregator tests | Pending |
| REQ-QUIC-TEMPLATE-RECEIVE | DGN-1794744879330763 | Real connected UDP loopback and cancellation tests | Pending |
| REQ-QUIC-TEMPLATE-WIRE | DGN-1794744880609592 | Locked native tests against RFC and independent packet fixtures | Pending |
| REQ-QUIC-TEMPLATE-PEER | DGN-1794744881534597 | Kotlin peer, failure, deadline, and retry tests | Pending |
| REQ-QUIC-TEMPLATE-PRESENTATION | DGN-1794744882640408 | Suite presentation tests | Pending |
| REQ-QUIC-TEMPLATE-NAMES | DGN-1794744883917139 | Suite presentation and locale checks | Pending |
| REQ-QUIC-TEMPLATE-TARGET | DGN-1794744884932948 | Kotlin target and runner tests | Pending |
