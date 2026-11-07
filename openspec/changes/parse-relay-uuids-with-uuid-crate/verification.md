---
task_id: RLY-1794036606000501
change: parse-relay-uuids-with-uuid-crate
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
| REQ-RELAY-UUIDS-WITH-UUID-CRATE-BEHAVIOR | RLY-1794036608444534 | Pending implementation and named gates | Required |
| REQ-RELAY-UUIDS-WITH-UUID-CRATE-COMPAT | RLY-1794036608929239 | Pending implementation and named gates | Required |

## Evidence boundary

Planning artifacts are complete; implementation evidence is pending. Record the exact implementation SHA and observed outputs before acceptance. A planning commit does not satisfy the local requirement. Update applicability if the actual diff adds device or artifact contracts.
