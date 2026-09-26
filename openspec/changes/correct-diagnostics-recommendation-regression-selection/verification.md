---
task_id: DGN-1790433260349809
change: correct-diagnostics-recommendation-regression-selection
commit_sha: null
local: required
local_evidence: All three focused regressions failed before their fixes and passed after. The full :core:diagnostics:testDebugUnitTest gate and :core:diagnostics:detekt passed with -Pripdpi.skipNativeBuild=true. Strict OpenSpec and taskctl validation passed.
remote_ci: required
remote_ci_evidence: null
device: not_applicable
device_evidence: These Kotlin selection rules have focused unit-test coverage and do not change device-specific behavior.
artifact: not_applicable
artifact_evidence: No distributable artifact is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DGN-1790433260349809-FREEZE | DGN-1790433444778107 | New test failed before mapping (1/1); all StrategyRecommendationEngineTest cases passed after mapping with -Pripdpi.skipNativeBuild=true. | passed |
| REQ-DGN-1790433260349809-PREVIOUS | DGN-1790433446065936 | Order test failed before fix (1/1); HomeCompositeRunJobsTest, DiagnosticsHomeCompositeRunServiceTest, and diagnostics detekt passed after fix with -Pripdpi.skipNativeBuild=true. | passed |
| REQ-DGN-1790433260349809-DNS | DGN-1790433447391364 | New test failed before fix (1/1); all ResolverRecommendationEngineComputeTest cases and the full diagnostics module unit gate passed after fix. | passed |
