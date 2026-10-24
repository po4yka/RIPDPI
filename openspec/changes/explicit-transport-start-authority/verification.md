---
task_id: SVC-1792838679434393
change: explicit-transport-start-authority
commit_sha: 47a5ee708a497086eb29739ca17f35e164119846
local: passed
local_evidence: Full core service suite passed 2296 tests with zero failures, errors, or skips on the merged base; ktlintCheck, detekt, architecture health, and task validation passed.
remote_ci: required
remote_ci_evidence: Final published candidate hosted gates remain required. Earlier source 38e64afcf9f097a60c8dd9146ed431c108fe35ef passed 56 checks with 19 planned skips; its Linux io_uring job 120911596401 passed 254 tests with no skips, positive WRITE_FIXED completions and zero fallback.
device: passed
device_evidence: All four local USB scenarios passed on source 47a5ee708a497086eb29739ca17f35e164119846 with the same genuine APK pair and complete owned cleanup. P0/P1 passed in 164.863 seconds, Hysteria in 161.927 seconds, AWG in 81.898 seconds and migration in 15.205 seconds. External paths, direct device UDP, bypass prevention and Wi-Fi/cellular handover remain unverified. Explicit AWG startup, peer-loss refusal and reconnect passed under production activation authority.
artifact: passed
artifact_evidence: Genuine arm64-v8a preparation passed at clean source 47a5ee708a497086eb29739ca17f35e164119846 in 2 minutes 3 seconds, with native compilation enabled and libXray verified. Independently verified preserved app SHA-256 f41bd47c48edb357f10d7f396399be6a997bc7151e9e69af8a31930079a77eef and test SHA-256 43a7587e955638efc870fcd9cb612fac9affe0cabffd0ad4d5a4616dcf15af2b.
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

## Final physical candidate 47a5ee70

All four local USB scenarios passed on source 47a5ee708a497086eb29739ca17f35e164119846 with the same genuine APK pair and complete owned cleanup. P0/P1 passed in 164.863 seconds, Hysteria in 161.927 seconds, AWG in 81.898 seconds and migration in 15.205 seconds. External paths, direct device UDP, bypass prevention and Wi-Fi/cellular handover remain unverified. Explicit AWG startup, peer-loss refusal and reconnect passed under production activation authority.

Genuine arm64-v8a preparation passed at clean source 47a5ee708a497086eb29739ca17f35e164119846 in 2 minutes 3 seconds, with native compilation enabled and libXray verified. Independently verified preserved app SHA-256 f41bd47c48edb357f10d7f396399be6a997bc7151e9e69af8a31930079a77eef and test SHA-256 43a7587e955638efc870fcd9cb612fac9affe0cabffd0ad4d5a4616dcf15af2b.
