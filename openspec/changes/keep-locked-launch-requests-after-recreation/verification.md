---
task_id: AND-1790433472437083
change: keep-locked-launch-requests-after-recreation
commit_sha: null
local: passed
local_evidence: "2026-09-26: :app:testGithubFullDebugUnitTest, :app:detekt, :app:ktlintMainSourceSetCheck, and :app:ktlintTestSourceSetCheck passed with -Pripdpi.skipNativeBuild=true. MainActivityShellControllerTest passed with deep link, shared diagnostics, import, and relock restoration cases."
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: not_applicable
artifact_evidence: No release artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RESTORE-LOCKED-LAUNCH | AND-1790433692316007 | `MainActivityShellControllerTest` deep link, shared diagnostics, and import recreation cases passed on 2026-09-26 | Passed locally; device pending |
| REQ-RESTORE-RELOCK | AND-1790433692316007 | `MainActivityShellControllerTest` pending relock case passed on 2026-09-26 | Passed locally; device pending |
