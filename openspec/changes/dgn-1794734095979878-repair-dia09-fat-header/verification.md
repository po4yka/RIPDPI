---
task_id: DGN-1794734095979878
change: dgn-1794734095979878-repair-dia09-fat-header
commit_sha: null
local: required
local_evidence: Run local catalog, native, report, and source checks on the combined tree.
remote_ci: required
remote_ci_evidence: Check the pushed commit.
device: not_applicable
device_evidence: The patch tests the native probe and report projection with loopback peers.
artifact: not_applicable
artifact_evidence: No release artifact is part of this change.
deployment: not_applicable
deployment_evidence: No deployment is part of this change.
---

# Verification

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DIA09-INPUT | DGN-1794734189422602 | Catalog mode and request-admission checks | Pending |
| REQ-DIA09-HTTP | DGN-1794734190617527 | Independent HTTP/1.1 peers and parser tests | Pending |
| REQ-DIA09-MEASURE | DGN-1794734191508610 | Socket write failure, EOF, RST, timeout, and short success | Pending |
| REQ-DIA09-ATTEMPTS | DGN-1794734191508610 | Loopback retry, cancellation, and comparison checks | Pending |
| REQ-DIA09-REPORT | DGN-1794734192514752 | Native report and Kotlin presentation checks | Pending |
