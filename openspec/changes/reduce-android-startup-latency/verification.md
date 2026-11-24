---
task_id: AND-1795494902099648
change: reduce-android-startup-latency
commit_sha: 92e178477a544f5cc3dd5a0dae950c31dbdb0289
local: required
local_evidence: 119 Kotlin and Room tests, 47 benchmark and CI contract tests, actionlint, just ci-gates, and Detekt for the four affected Kotlin modules passed. The current detector accepted the published API 34 measurements and reference.
remote_ci: required
remote_ci_evidence: CI run 45334470980 succeeded in Android Macrobenchmark and Gradle static analysis. Its aggregate CI status failed in the Rust root helper test rejected_replacement_reports_an_error_even_while_old_backend_is_running.
device: required
device_evidence: Pixel 8 Pro API 37. Cold TTID median decreased from 422.5 to 377 ms in 10 launches per build. Warm TTID median decreased from 154 to 145 ms in five launches per build. Full display was reported after the change.
artifact: required
artifact_evidence: Arm64 githubBenchmark APKs with embedded revisions 3304e8d70 and 44d29ff60 used verified native inputs. Perfetto recorded startup steps and full display on the second build. GitHub Actions artifact 14895251895 contains the measured API 34 CI reference.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-ANDROID-STARTUP-RECOVERY | AND-1795495009491088 | Startup, diagnostics, and Room tests | Passed |
| REQ-ANDROID-STARTUP-METRICS | AND-1795495010036353 | Physical Pixel startup metrics | Passed |
| REQ-ANDROID-STARTUP-REGRESSION | AND-1795495010525115 | Managed benchmark and regression detector | Passed |
