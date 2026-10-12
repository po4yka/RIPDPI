---
task_id: UIX-1791793531264679
change: material3-readability
commit_sha: null
local: required
local_evidence: null
remote_ci: not_applicable
remote_ci_evidence: Local implementation and main commits only; push is not requested.
device: not_applicable
device_evidence: User requested local visual checks; use fresh host-rendered images.
artifact: required
artifact_evidence: null
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UI-READABLE-TYPE | UIX-1791793632932195 | Theme tests and fresh UI renders | Pending |
| REQ-UI-SETTINGS-LAYOUT | UIX-1791793633865680 | SettingsRowTest with long values and 2x font | Pending |
| REQ-UI-DIAGNOSTICS-TABS | UIX-1791793634763649 | DiagnosticsSectionSwitcherTest and fresh renders | Pending |
| REQ-UI-PRESERVE-CONTRACTS | UIX-1791793635678588 | App/service lint, light/dark/2x renders, final diff | Pending |
