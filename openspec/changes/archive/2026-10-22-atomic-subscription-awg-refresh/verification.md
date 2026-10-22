---
task_id: DAT-1792683530998740
change: atomic-subscription-awg-refresh
commit_sha: ede4099f5e53023c03e4e6875cdb8521cb627d56
local: passed
local_evidence: Machine-gated affected-module and both app-variant JVM tests plus staticAnalysis passed on 2026-10-22; 9590 JVM tests and 23 Detekt-rule tests passed, with runtime-state reused up-to-date in the final run. Task contracts and architecture checks passed.
remote_ci: passed
remote_ci_evidence: CI run 39815952976 attempt 1 passed on b5b09af6ea93f5c076232e5d97529aa79ff93396; all 64 jobs resolved with 46 success and 18 routed skips. Rebase-merged source ede4099f5e53023c03e4e6875cdb8521cb627d56 has the identical complete Git tree. Required CI, all five API Full/Simple matrices, JNI and real Xray TUN acceptance passed.
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
| REQ-AWG-OWNER | DAT-1792683671528669 | Five ownership regressions pass in both app variants, including same-clock accepted generations and duplicate-identity parse rejection | Passed |
| REQ-AWG-COMMIT | DAT-1792683672362814 | Six mutation regressions and existing accepted rotation pass; recovery, cancellation, observer publication, revision bounds and unreadable catalog guards verified | Passed |

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

The Simple embedded bundle is the committed CI fixture copied into the ignored local asset path. Native builds were disabled for these local JVM/lint checks; the separate hosted native/emulator CI described below passed. No physical-device, filtered-path, server deployment or credential acceptance is claimed.

## Accepted source and hosted validation

PR #647 was rebase-merged as `ede4099f5e53023c03e4e6875cdb8521cb627d56` after all checks passed on `b5b09af6ea93f5c076232e5d97529aa79ff93396`. A fresh fetch and whole-tree comparison found no differences; both revisions have tree `72c9edb2b3c770ef5a2379e3bca7e0dead916aa7`. The source commit before its metadata integration was `93cbdb6c5fc70181a76f7288ee903aa13c13fd66`; its app/core files equal the locally tested staged tree.

- [CI run 39815952976, attempt 1](https://github.com/po4yka/RIPDPI/actions/runs/39815952976): completed success, 64 jobs resolved, 46 success, 18 routed skips, zero pending or failed jobs. Required `ci-required` job `119977349405` passed.
- Full hosted library and both app JVM suites, static analysis, coverage, native checks, debug builds and all release shards passed.
- API 27 (`119968056901`), 33 (`119968056860`), 35 (`119968056984`), 36 (`119968056955`) and 37 (`119968056846`) each passed Full and Simple instrumentation plus required evidence validation. API 35 also passed JNI strategy instrumentation and real Xray-provider TUN acceptance.
- CodeQL run `39815953065`, Secret Scan `39815953051` and harness checks `39815952977` passed. The final PR rollup had 69 completed checks and zero pending or failing checks.
- Fresh review inspection found no review threads or submitted reviews. Cloud code/security review could not execute because account usage limits were reached; it is not credited as passed. Independent read-only source and delegation review approved the change.

The hosted emulator evidence is distinct from physical-device, filtered-network and deployed-server acceptance, none of which is claimed. The initial source defect is resolved by the real accepted AWG write and recovery path, not only by stale-response rejection.
