---
task_id: ENG-1794638026548237
change: quic-crypto-split-audit-fixes
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: Local regression fixes do not claim Android device acceptance.
artifact: required
artifact_evidence: null
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-QUIC-ORIGINAL | ENG-1794638148243320 | Pending regression and adapter tests | required |
| REQ-QUIC-TTL | ENG-1794638149872072 | Pending projection tests | required |
| REQ-QUIC-OVERLAP | ENG-1794638151583022 | Pending packet tests | required |
| REQ-QUIC-COUNT | ENG-1794638155153185 | Pending Kotlin and Rust budget tests | required |
| REQ-QUIC-MINIMUM | ENG-1794638165336906 | Pending minimum input tests | required |
| REQ-QUIC-SCOPE | ENG-1794638177788370 | Pending combined wire and integration checks | required |
