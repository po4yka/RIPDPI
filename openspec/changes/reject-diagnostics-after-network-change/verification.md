---
task_id: DGN-1791479038917244
change: reject-diagnostics-after-network-change
commit_sha: null
local: passed
local_evidence: Diagnostics tests 1482 passed; service tests 2195 passed; architecture health and locked Cargo metadata passed. Full staticAnalysis passed with cached dependencies in offline mode.
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: This change uses the existing physical observer; deterministic callback and coordinator tests cover the guard. Live carrier acceptance is not claimed.
artifact: not_applicable
artifact_evidence: No APK or native artifact is changed by the JVM control-plane fix.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DGN-SCOPE-001 | DGN-1791479258111777 | Original A-to-B regression failed before the fix with an unexpected remembered policy. The same regression now passes. | passed |
| REQ-DGN-SCOPE-001 | DGN-1791479259597917 | 1482 diagnostics tests and 2195 service tests passed, including stable scope, A-to-B-to-A, missing evidence, recovery, and the DNS reprobe resume window. | passed |
| REQ-DGN-SCOPE-002 | DGN-1791479260710860 | Module tests, full staticAnalysis, architecture health, locked Cargo metadata, and independent review passed. | passed |

## Local commands

- RED: `./gradlew :core:diagnostics:testDebugUnitTest --tests '*DiagnosticsScanStrategyProbeCoordinatorTest.background automatic probing preserves report without recommendation when network changes' -Pripdpi.skipNativeBuild=true --max-workers=4 --console=plain`. One expected assertion failure before the fix.
- GREEN: `./gradlew :core:diagnostics:testDebugUnitTest :core:service:testDebugUnitTest -Pripdpi.skipNativeBuild=true --max-workers=4 --console=plain --no-daemon`. 3677 tests passed with no skips.
- Architecture: `python3 scripts/ci/check_architecture_health.py`. No new or worsened indicators.
- Cargo: `cargo metadata --manifest-path native/rust/Cargo.toml --locked`. Passed.
- Review: a read-only sub-agent reviewed the final logic. Its findings were fixed and covered by regression tests. The reported constructor compile error was fixed; production and test compilation then passed.

- Static analysis: `./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true --max-workers=4 --console=plain --no-daemon --offline`. Passed after the complexity findings were fixed. The online attempt waited on Maven metadata in Android Lint. No rules or baselines were disabled.

## Limits

The JVM lane disables native builds. This result does not establish APK packaging, device handover, or live ISP acceptance. The first Gradle retry failed because its daemon could not update cache files; a fresh single-use daemon completed the gates. Remote CI must be checked for the pushed commit.
