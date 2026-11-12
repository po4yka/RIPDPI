---
task_id: TRN-1794466458131158
change: repair-warp-preset-contracts
commit_sha: null
local: required
local_evidence: Focused regressions and combined gates are pending implementation.
remote_ci: required
remote_ci_evidence: No repaired source has been pushed yet.
device: not_applicable
device_evidence: No device or external transport acceptance is requested; host UI rendering and local peer checks are tracked separately.
artifact: not_applicable
artifact_evidence: This source repair does not publish a package or change JNI signatures.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-WARP-PRESET-WIRE | TRN-1794466908354230 | Raw WireGuard peer, reserved MAC and cookie regressions | pending |
| REQ-WARP-PRESET-ENABLED | TRN-1794466913001475 | Selector matrix and JSON/native preference round trips | pending |
| REQ-WARP-PRESET-HINT | TRN-1794466918147610 | UI recommendation semantic check | pending |
| REQ-WARP-PRESET-HINT-REFRESH | TRN-1794466921219277 | Observed network transitions without settings changes | pending |

## Limits

No public WARP endpoint, real credentials, root, physical transport, release or
production deployment is used. The pinned generic AWG suite remains required.
