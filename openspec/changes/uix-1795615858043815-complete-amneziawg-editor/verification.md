---
task_id: UIX-1795615858043815
change: uix-1795615858043815-complete-amneziawg-editor
commit_sha: null
local: required
local_evidence: "./gradlew :app:testGithubDebugUnitTest --tests com.poyka.ripdpi.ui.screens.awg.* --tests com.poyka.ripdpi.ui.screens.profiles.* passed (192 tests, 0 failures; after review fix the awg package reran at 120 tests, 0 failures, AmneziaWgProfileSaveTest 7/7); :app:verifyRoborazziGithubDebug --tests AmneziaWgProfileScreenshotTest passed after blessing 6 goldens; :app:lintGithubDebug and :core:service/:core:engine compileStandardDebugUnitTestKotlin passed (2026-11-25)."
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this change.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UIX-1795615858043815-001 | UIX-1795615858566933 | `AmneziaWgProfileSaveTest`: named save, rename of the same row, reopened editor shows the name, blank name keeps the default. Lists render `SavedAwgProfile.name` unchanged. | local pass; device pending |
| REQ-UIX-1795615858043815-002 | UIX-1795615859012201 | `AmneziaWgProfileSaveTest`: generation fills an empty key, exposes the public key until the key changes, never replaces a key, reports failure. FLAG_SECURE stays through `RelayEditorScaffold`. Native generator on device not exercised. | local pass; device pending |
| REQ-UIX-1795615858043815-003 | UIX-1795615859487299 | `AmneziaWgProfileSaveTest`: Save with VPN consent required emits no consent request, activates nothing, and stores one row. | local pass; device pending |
