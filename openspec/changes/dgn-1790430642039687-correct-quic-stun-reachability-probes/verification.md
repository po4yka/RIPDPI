---
task_id: DGN-1790430642039687
change: dgn-1790430642039687-correct-quic-stun-reachability-probes
commit_sha: null
local: passed
local_evidence: Focused QUIC and STUN regressions, full :core:diagnostics:testDebugUnitTest, and :app:compileGithubFullDebugKotlin passed with -Pripdpi.skipNativeBuild=true on 2026-09-26. The flag omits JNI libraries and is not an APK or device gate.
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: not_applicable
artifact_evidence: No distributable artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-QUIC-VALID-INITIAL | DGN-1790430818948716 | Production suite no longer selects bundled 125-byte fixtures. Focused QUIC tests and `:app:compileGithubFullDebugKotlin` passed with `-Pripdpi.skipNativeBuild=true`. | passed |
| REQ-QUIC-BUILD-FAILURE | DGN-1790430818948716 | `nativePacketFactoryRejectsBuildFailure` passed; the suite maps factory failure to a failed probe row. | passed |
| REQ-STUN-RESPONSE-MATCH | DGN-1790430824485948 | Matching binding success accepted; wrong type, cookie, transaction ID, length, source address, and source port rejected. Focused tests and full module suite passed. | passed |
