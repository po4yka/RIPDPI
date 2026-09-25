---
task_id: UIX-1790339200447975
change: expose-root-and-finalmask-controls
commit_sha: null
local: required
local_evidence: Focused root mode, Finalmask, and ConfigViewModel tests passed on 2026-09-25; app/service lint and staticAnalysis passed with ripdpi.skipNativeBuild=true.
remote_ci: required
remote_ci_evidence: Pending push and hosted CI on integrated main.
device: required
device_evidence: Pending connected-device root mode and relay editor inspection.
artifact: not_applicable
artifact_evidence: No distributable artifact is owned by this UI fix.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-ROOT-MODE-OPT-IN | UIX-1790339352495635 | RootModeStrategiesControlsTest and RootModeStrategiesViewModelTest passed; switch visible and persisted. | passed |
| REQ-FINALMASK-VALIDITY | UIX-1790339352495635 | RelayFinalmaskVisibilityTest and ConfigViewModelTest passed for supported kinds and stale unsupported values. | passed |

## Visual check

Targeted Roborazzi verification produced expected differences for `rootModeEnabledStrategiesScreen` and `rootModeDisabledStrategiesScreen`: the new switch card moves the strategy or disabled-state card down. Both actual images were inspected for clipping and spacing. Fixtures remain unchanged pending explicit approval for this screenshot family.
