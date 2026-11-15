---
task_id: DGN-1794730805168562
change: repair-dia02-tls-evidence
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: Host regressions cover the repair. Android device and route acceptance were not requested.
artifact: not_applicable
artifact_evidence: No release artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence target | Result |
|---|---|---|---|
| REQ-DIA02-F1 | DGN-1794730885862088 | A complete locally written ClientHello before a peer alert or local trust error | Pending |
| REQ-DIA02-F2 | DGN-1794730895791189 | Distinct outcomes at separate loopback peers and fresh ECH retry | Pending |
| REQ-DIA02-F3 | DGN-1794730896305094 | Peer client-auth/protocol-version alerts, local trust error, and quick response | Pending |
| REQ-DIA02-F4 | DGN-1794730896780389 | Failed first candidate, known and unknown peer, and authenticated ECH rejection | Pending |
| REQ-DIA02-F5 | DGN-1794730897256277 | Completed evidence before cancellation and ECH-only success or trust failure | Pending |
| REQ-DIA02-F6 | DGN-1794730897761185 | Known-answer JA3 with GREASE, fragmentation and malformed lengths | Pending |
| REQ-DIA02-F7 | DGN-1794730898243492 | Negotiated and absent ALPN in the strategy and web projections | Pending |
| REQ-DIA02-F8 | DGN-1794730898711547 | Default vs custom trust and rejected retry without authentication | Pending |
