---
task_id: AND-1790430183226560
change: and-1790430183226560-block-deep-links-until-app-unlock
commit_sha: null
local: passed
local_evidence: Targeted :app:testGithubFullDebugUnitTest passed 52 tests; :app:detekt and app main/test ktlint checks passed with native build skipped.
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: not_applicable
artifact_evidence: No independent artifact is owned by this source change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-APP-LOCK-NAVIGATION-GATE | AND-1790430352698533 | MainActivityPermissionTest and RipDpiNavHostLogicTest; device check pending | passed |
| REQ-APP-LOCK-NAVIGATION-RESUME | AND-1790430352698533 | MainActivityShellControllerTest; device check pending | passed |
