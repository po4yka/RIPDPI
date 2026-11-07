---
task_id: DNS-1794036588840800
change: parse-native-urls-with-url-crate
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
| REQ-NATIVE-URLS-WITH-URL-CRATE-BEHAVIOR | DNS-1794036591381058 | Pending implementation and named gates | Required |
| REQ-NATIVE-URLS-WITH-URL-CRATE-COMPAT | DNS-1794036591886250 | Pending implementation and named gates | Required |

## Evidence boundary

Planning artifacts are complete; implementation evidence is pending. Record the exact implementation SHA and observed outputs before acceptance. A planning commit does not satisfy the local requirement. Update applicability if the actual diff adds device or artifact contracts.
