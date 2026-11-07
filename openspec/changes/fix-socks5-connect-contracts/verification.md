---
task_id: PRX-1794071497680636
change: fix-socks5-connect-contracts
commit_sha: null
local: required
local_evidence: Targeted and affected-crate Cargo tests plus ci-gates and independent review are pending.
remote_ci: not_applicable
remote_ci_evidence: Local gates are required; hosted CI execution is not claimed by this change.
device: not_applicable
device_evidence: Host-loopback wire contract corrections; no Android lifecycle changes or device acceptance claim.
artifact: not_applicable
artifact_evidence: No shipped binary or golden artifact is updated.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-S5-SUCCESS | PRX-1794071600786245 | delayed CONNECT loopback tests | pending |
| REQ-S5-HEADER | PRX-1794071601228418 | header parser and loopback tests | pending |
| REQ-S5-ATYP | PRX-1794071601781187 | unsupported ATYP wire test | pending |
| REQ-S5-POLICY | PRX-1794071602368425 | self-target and Block wire tests | pending |
| REQ-S5-AUTH | PRX-1794071602984411 | zero-length auth tests | pending |
| REQ-S5-COMPAT | PRX-1794071600786245 | affected crate tests and network E2E | pending |
