---
task_id: DAT-1792683530998740
change: atomic-subscription-awg-refresh
commit_sha: null
local: passed
local_evidence: Machine-gated affected-module and both app-variant JVM tests plus staticAnalysis passed on 2026-10-22; 9590 JVM tests and 23 Detekt-rule tests passed, with runtime-state reused up-to-date in the final run. Task contracts and architecture checks passed.
remote_ci: required
remote_ci_evidence: Full required CI must pass on the final source revision.
device: not_applicable
device_evidence: No physical-device or filtered-network acceptance requested; required hosted emulator coverage remains part of remote CI.
artifact: not_applicable
artifact_evidence: No release artifact requested.
deployment: not_applicable
deployment_evidence: No server deployment or credential action authorized for this client fix.
---

# Verification

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-AWG-OWNER | DAT-1792683671528669 | Five ownership regressions pass in both app variants, including same-clock accepted generations and duplicate-identity parse rejection | Local passed; remote CI pending |
| REQ-AWG-COMMIT | DAT-1792683672362814 | Six mutation regressions and existing accepted rotation pass; recovery, cancellation, observer publication, revision bounds and unreadable catalog guards verified | Local passed; remote CI pending |

## Local validation

The final machine-gated command completed successfully in 16m 32s:

```sh
BUILD_GATE_TIMEOUT=1200 CARGO_BUILD_JOBS=4 CMAKE_BUILD_PARALLEL_LEVEL=4 build-gate -- ./gradlew :core:data:runtime-state:testDebugUnitTest :core:data:testDebugUnitTest :core:service:testDebugUnitTest :app:testGithubFullDebugUnitTest :app:testGithubSimpleDebugUnitTest staticAnalysis -Pripdpi.skipNativeBuild=true --max-workers=4
```

- Runtime-state: 286 passing tests from the first run; Gradle reused this unchanged task as `UP-TO-DATE` in the final run.
- Core data: 916; core service: 2266; Full app: 2969; Simple app: 3153. The final run produced fresh XML reports with zero failures, errors or skipped tests.
- Static analysis passed, including Detekt, ktlint, Android lint for both app variants and 23 Detekt-rule tests.
- `python3 scripts/ci/check_architecture_health.py`: 21 existing indicators, zero new, worsened or stale entries.
- `./taskctl validate --base origin/main`: 148 tasks and 454 steps valid before the prior feature's metadata integration.
- Independent read-only review approved ownership, publication, cancellation and unreadable-storage boundaries, then rechecked the runtime delegation.

The initial gate wait did not execute tests. The first actual run passed the three affected modules and Full app, then failed Detekt's function-count limit before Simple execution. Moving the existing helper behind the runtime's established interface-delegation pattern resolved that finding; no thresholds or baselines changed. A red pre-fix runtime result was not observed.

The Simple embedded bundle is the committed CI fixture copied into the ignored local asset path. Native builds were disabled for these JVM/lint checks; hosted native/emulator CI remains required. No physical-device, filtered-path, server deployment or credential acceptance is claimed.
