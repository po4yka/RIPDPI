---
task_id: DAT-1792661188293948
change: offline-reserve-profiles
commit_sha: null
local: required
local_evidence: "Formatting, strict OpenSpec/task validation and architecture-health pass. Combined JVM/staticAnalysis command could not start: build-gate timed out after 120 seconds (exit 75), with both machine slots occupied. No JVM, staticAnalysis, AndroidKeyStore or device success is claimed."
remote_ci: required
remote_ci_evidence: "Pending. This PR targets the payload-failover dependency branch; the current CI pull_request trigger is limited to main."
device: not_applicable
device_evidence: Source PR uses local socket and restart tests; physical-device and filtered-network acceptance are not claimed.
artifact: not_applicable
artifact_evidence: No release artifact is requested.
deployment: not_applicable
deployment_evidence: User explicitly excludes server deployment.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RESERVE-RELOAD | DAT-1792661307731110 | Offline restart and real HTTPS/SOCKS selector test; affected module gates | Implemented; JVM execution blocked |
| REQ-RESERVE-STORAGE | DAT-1792661302262545 | Runtime-state corrupt/read/write failure regressions | Implemented; JVM execution blocked |
| REQ-RESERVE-REFRESH | DAT-1792661307171939 | Unusable/stale refresh and authoritative replacement regressions | Implemented; JVM execution blocked |

## Local commands

Passed: explicit-file `ktlint` on changed Kotlin/Gradle files, `git diff --check`, `./taskctl validate`, `./taskctl openspec cli validate offline-reserve-profiles --strict`, and `python3 scripts/ci/check_architecture_health.py`.

Attempted through the machine-wide gate, but Gradle never started:

```sh
BUILD_GATE_TIMEOUT=120 CMAKE_BUILD_PARALLEL_LEVEL=4 build-gate -- env -u CARGO_BUILD_JOBS ./gradlew \
  :core:data:runtime-state:testDebugUnitTest :core:service:testDebugUnitTest \
  :app:testGithubFullDebugUnitTest staticAnalysis \
  --max-workers=4 -Pripdpi.nativeCpuBudget=4 -Pripdpi.skipNativeBuild=true
```

Task acceptance remains open pending JVM and CI validation. The socket integration uses real local TLS/SOCKS transfer and a temporary-file catalogue port; it does not invoke the native relay engine or the platform keystore. Its scenarios are implemented, not yet executed. No server or credential action was performed.

## Review follow-up: early catalogue observers

The repository observation stream now performs no reads and publishes no initial empty fallback. Guarded `list()` publishes a validated snapshot under the mutation mutex. Corrupt-storage and startup-retry regressions now start a collector before loading; the core-data flow test explicitly performs the initial load. Independent source review found no remaining issue.

Rechecked formatting, task/OpenSpec contracts, architecture-health and diff whitespace successfully. The follow-up runtime-state/app JVM and staticAnalysis command again timed out at build-gate after 120 seconds (exit 75), before Gradle started. Before acceptance, rerun the combined command above and include `:core:data:testDebugUnitTest` for the updated observer contract test.
