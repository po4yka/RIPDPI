---
task_id: SVC-1792838679434393
change: explicit-transport-start-authority
commit_sha: null
local: passed
local_evidence: Full core service suite passed 2296 tests with zero failures, errors, or skips on the merged base; ktlintCheck, detekt, architecture health, and task validation passed.
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
| REQ-TRANSPORT-CAPTURED-AUTHORITY | SVC-1792838724128146 | Five focused activation tests within the full 2296-test service suite | Passed locally |
| REQ-TRANSPORT-STALE-AUTHORITY | SVC-1792838725753585 | Missing and stale command, cancellation, and existing failover tests passed; owned physical AWG run pending | Passed locally; device required |

## Pre-fix evidence

The owned physical AWG run at `40d76fe9f66888ce5cf0bf318bfad0d8f5a7b812` failed before native handshake with `Runtime start command was not captured` at ServiceRuntimeStartStopOrchestrator. Scoped logs show ServiceShellDelegate explicit transport activation calling cold startup without RuntimeCommandStartAuthority. Cleanup passed. This failure is separate from post-fix acceptance.

The focused JVM RED command was cancelled while it waited at the shared build gate, before Gradle started. No JVM RED result is claimed. Implementation proceeds from the observed physical failure; the full service suite remains required.

## Local result

Base commit: `5bcbea7a71f96c00be801af3cfdbea4a2ec62139`. The final command completed with exit code 0:

```text
build-gate -- env JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home ./gradlew :core:service:testDebugUnitTest :core:service:ktlintCheck :core:service:detekt -Pripdpi.skipNativeBuild=true --max-workers=4 --console=plain
```

All 2296 tests in 309 suites passed with zero failures, errors, or skips. The five new activation regressions passed. Architecture health reported no new or worsened indicators. Independent source review approved the production change and tests. No baseline changed. The earlier length and formatting failures were corrected before this final passing run.

Hosted CI, rebuilt artifacts, and the final owned physical AWG run remain required. The task stays open until those results exist.
