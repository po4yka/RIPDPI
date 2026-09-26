---
task_id: AND-1790432494546766
change: preserve-pending-archive-export
commit_sha: null
local: passed
local_evidence: "2026-09-26: :app:testGithubFullDebugUnitTest, :app:detekt, :app:ktlintMainSourceSetCheck, and :app:ktlintTestSourceSetCheck passed with -Pripdpi.skipNativeBuild=true; PendingDiagnosticsArchiveStateTest passed 3 tests."
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
| REQ-ARCHIVE-RECREATION | AND-1790432652321896 | `PendingDiagnosticsArchiveStateTest` (3 passed) and `:app:testGithubFullDebugUnitTest` passed on 2026-09-26 | Passed locally; device pending |
