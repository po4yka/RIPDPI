---
task_id: DGN-1790339654179112
change: remove-inert-diagnostic-row-actions
commit_sha: null
local: required
local_evidence: Focused DiagnosticsRowActionTest, app lint, and staticAnalysis passed on 2026-09-25 with ripdpi.skipNativeBuild=true.
remote_ci: required
remote_ci_evidence: Pending push and hosted CI on integrated main.
device: required
device_evidence: Pending connected-device TalkBack inspection of diagnostic rows.
artifact: not_applicable
artifact_evidence: No distributable artifact is owned by this UI fix.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DIAGNOSTIC-ROW-SEMANTICS | DGN-1790339717443198 | DiagnosticsRowActionTest passed for diagnostic event, session, probe, and history event rows with and without actions. | passed |
