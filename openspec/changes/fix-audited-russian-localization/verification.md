---
task_id: UIX-1791805113916673
change: fix-audited-russian-localization
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: This change requires local API-level resource tests and screenshot verification. A physical-device acceptance matrix is not requested and is not claimed.
artifact: not_applicable
artifact_evidence: No release APK or signed distribution artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-L10N-MEANING | UIX-1791805259890154 | Russian resource semantic regression tests and review | Required |
| REQ-L10N-LOCALE | UIX-1791805277481999 | API 32 app-locale and non-Activity resource tests | Required |
| REQ-L10N-COUNTS | UIX-1791805277481999 | Russian 1/2/5/21/22/25 event and notification count tests | Required |
| REQ-L10N-PRESENTATION | UIX-1791805293603516 | Russian DPI, routing, and signature presenter tests | Required |
| REQ-L10N-PARITY | UIX-1791805309122591 | Locale parity/export, app/service lint, and combined-tree tests | Required |

## Scope and evidence limits

Hosted CI is separate from local gates. Report the pushed SHA and actual hosted status after push. Do not claim device or release acceptance from Robolectric or screenshots.
