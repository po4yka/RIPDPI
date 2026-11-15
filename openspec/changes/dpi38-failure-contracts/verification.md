---
task_id: ENG-1794722736729815
change: dpi38-failure-contracts
commit_sha: null
local: required
local_evidence: Pending regression tests, affected Rust suites, Clippy, workspace check and ci-gates.
remote_ci: not_applicable
remote_ci_evidence: Local source repair acceptance does not depend on a hosted runner; any push-triggered CI is reported separately.
device: not_applicable
device_evidence: These internal Rust behavior fixes use local socket and independent TLS oracles; physical root and external-network acceptance are outside the authorized test scope.
artifact: not_applicable
artifact_evidence: No release or packaged binary is delivered by this source change; native source compilation is part of local validation.
deployment: not_applicable
deployment_evidence: No production deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DPI38-F1 | ENG-1794722945021715 | Required red/green regression and affected crate gate; see design | Pending |
| REQ-DPI38-F2 | ENG-1794722951308143 | Required red/green regression and affected crate gate; see design | Pending |
| REQ-DPI38-F3 | ENG-1794722957091594 | Required red/green regression and affected crate gate; see design | Pending |
| REQ-DPI38-F4 | ENG-1794722960734723 | Required red/green regression and affected crate gate; see design | Pending |
| REQ-DPI38-F5 | ENG-1794722964687558 | Required red/green regression and affected crate gate; see design | Pending |
| REQ-DPI38-F6 | ENG-1794722968859091 | Required red/green regression and affected crate gate; see design | Pending |
| REQ-DPI38-F7 | ENG-1794722971027950 | Required red/green regression and affected crate gate; see design | Pending |
| REQ-DPI38-F8 | ENG-1794722972628499 | Required red/green regression and affected crate gate; see design | Pending |
| REQ-DPI38-F9 | ENG-1794722974631962 | Required red/green regression and affected crate gate; see design | Pending |
| REQ-DPI38-SCOPE | ENG-1794722976807450 | Review, workspace check, cargo metadata --locked, ci-gates and final diff | Pending |
