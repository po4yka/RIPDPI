---
task_id: AND-1794036523065646
change: parse-import-deep-links-with-uri
commit_sha: null
local: required
local_evidence: null
remote_ci: not_applicable
remote_ci_evidence: This scope authorizes local work and main commits; remote publication is not owned by this task.
device: not_applicable
device_evidence: No Android lifecycle, JNI, or device socket ownership change is planned. Use the child host or Robolectric gates; revisit this category if implementation expands those contracts.
artifact: not_applicable
artifact_evidence: No release APK, native binary publication, or golden blessing is requested.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-IMPORT-DEEP-LINKS-WITH-URI-BEHAVIOR | AND-1794036525535986 | Pending implementation and named gates | Required |
| REQ-IMPORT-DEEP-LINKS-WITH-URI-COMPAT | AND-1794036525967551 | Pending implementation and named gates | Required |

## Evidence boundary

Planning artifacts are complete; implementation evidence is pending. Record the exact implementation SHA and observed outputs before acceptance. A planning commit does not satisfy the local requirement. Update applicability if the actual diff adds device or artifact contracts.
