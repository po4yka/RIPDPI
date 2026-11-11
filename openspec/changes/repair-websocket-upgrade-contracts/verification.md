---
task_id: TRN-1794383445146007
change: repair-websocket-upgrade-contracts
commit_sha: null
local: required
local_evidence: Owning wire regressions, affected Rust checks, source gates and review are pending.
remote_ci: not_applicable
remote_ci_evidence: This repair requires observed local gates before the authorized push; hosted CI is reported separately.
device: not_applicable
device_evidence: Source repair of host-testable transport contracts; no physical-device or public-peer acceptance is claimed.
artifact: not_applicable
artifact_evidence: This change does not produce a release artifact.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-WS-EXTENSIONS | TRN-1794383519591425 | Independent raw-wire regression and owning suite; pending | pending |
| REQ-WS-CONNECTION | TRN-1794383519591425 | Independent raw-wire regression and owning suite; pending | pending |
| REQ-WS-ERRORS | TRN-1794383522543227 | Independent raw-wire regression and owning suite; pending | pending |
| REQ-UPGRADE-RESPONSE | TRN-1794383523565872 | Independent raw-wire regression and owning suite; pending | pending |
| REQ-WS-CLOSE | TRN-1794383526870583 | Independent raw-wire regression and owning suite; pending | pending |
| REQ-UPGRADE-REQUEST | TRN-1794383523565872 | Independent raw-wire regression and owning suite; pending | pending |
| REQ-WS-PRIVACY-LIMITS | TRN-1794383530212567 | Independent raw-wire regression and owning suite; pending | pending |
