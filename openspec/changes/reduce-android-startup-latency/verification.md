---
task_id: AND-1795494902099648
change: reduce-android-startup-latency
commit_sha: bbf19d4a79068d0dec95e173a42de8e107fe3c18
local: required
local_evidence: 119 Kotlin and Room tests, 46 benchmark and CI contract tests, actionlint, just ci-gates, and Detekt for the four affected Kotlin modules passed.
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: Pixel 8 Pro API 37. Cold TTID median decreased from 422.5 to 377 ms in 10 launches per build. Warm TTID median decreased from 154 to 145 ms in five launches per build. Full display was reported after the change.
artifact: required
artifact_evidence: Arm64 githubBenchmark APKs with embedded revisions 3304e8d70 and 44d29ff60 used verified native inputs. Perfetto recorded startup steps and full display on the second build.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-ANDROID-STARTUP-RECOVERY | AND-1795495009491088 | Startup, diagnostics, and Room tests | Passed |
| REQ-ANDROID-STARTUP-METRICS | AND-1795495010036353 | Physical Pixel startup metrics | Passed |
| REQ-ANDROID-STARTUP-REGRESSION | AND-1795495010525115 | Managed benchmark and regression detector | Pending |
