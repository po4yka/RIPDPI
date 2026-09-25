---
task_id: RLY-1790340340749310
change: expose-relay-mode-editor-kinds
commit_sha: null
local: required
local_evidence: 76 targeted app unit, Compose, and navigation tests; app/service lint; app detekt; both Kotlin formatting checks; task and strict OpenSpec validation; translation export; Cargo metadata; and architecture health passed on the combined tree with ripdpi.skipNativeBuild=true.
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: required
artifact_evidence: null
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RLY-1790340340749310-001 | RLY-1790340429179687 | Compose touch tests opened all six kinds from preview rows and Apps Script beyond preview; expanded-list semantics and navigation registry tests passed. | passed |
| REQ-RLY-1790340340749310-002 | RLY-1790340429179687 | Targeted repository and dedicated editor tests preserved imported IDs, fields, and credentials. | passed |
| REQ-RLY-1790340340749310-003 | RLY-1790340431151340 | Validation tests rejected missing secrets, Apps Script VPN use and disabled TLS verification, and invalid Shadowsocks 2022 keys; app/service lint, detekt, both Kotlin formatting checks, and translation export passed. | passed |
| REQ-RLY-1790340340749310-004 | RLY-1790340431151340 | Existing credential rebinding test and new stale Mieru edit test passed. | passed |
