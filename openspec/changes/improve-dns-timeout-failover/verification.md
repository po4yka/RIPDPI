---
task_id: SVC-1786488973639528
change: improve-dns-timeout-failover
commit_sha: null
local: required
local_evidence: Focused bootstrap-timeout regression, the complete controller test class, the full core service unit-test suite, and staticAnalysis passed locally.
remote_ci: required
remote_ci_evidence: Pending push of the exact implementation SHA.
device: not_applicable
device_evidence: The controller transition is deterministic JVM logic with no Android framework dependency.
artifact: not_applicable
artifact_evidence: This change produces no APK, AAB, native library, or deployment artifact.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-EAGER-BOOTSTRAP-TIMEOUT | SVC-1786489128455286 | `bootstrap timeout eagerly selects next encrypted resolver without persisting block` passed. | passed |
| REQ-TRANSIENT-TIMEOUT-MEMORY | SVC-1786489128455286 | The regression asserts the timed-out path is attempted for the session but absent from both blocked-path state and persistent storage. | passed |
| REQ-STRICT-ENCRYPTED-FAILOVER | SVC-1786489128455286 | The complete controller class and `:core:service:testDebugUnitTest` passed with the existing encrypted candidate-plan and exhaustion coverage. | passed |
| REQ-COMPATIBLE-TIMEOUT-POLICY | SVC-1786489128455286 | `staticAnalysis` passed; no serialized contract, JNI, protobuf, or fallback policy changed. | passed |
