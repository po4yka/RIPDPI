---
task_id: AND-1795444347241489
change: exclude-play-privileged-features
commit_sha: null
local: required
local_evidence: null
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: required
artifact_evidence: null
deployment: not_applicable
deployment_evidence: Release publication is a separate operation.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DISTRIBUTION-ARTIFACT | AND-1795444402230111 | Shared and variant Android packaging checks. | Pending |
| REQ-DISTRIBUTION-RUNTIME | AND-1795444402897115 | Native and Android runtime and migration tests. | Pending |
| REQ-DISTRIBUTION-RELEASE | AND-1795444403544089 | Signed release artifact checks and device acceptance. | Pending |

## Local results

- Merged Play, F-Droid, and GitHub debug assets for arm64-v8a in one Gradle invocation. The helper executable, nfqws2, and the generated zapret2 asset tree were present in F-Droid and GitHub only.
- The Play-only asset merge task graph excluded the extended asset stage and both executable producer tasks.
