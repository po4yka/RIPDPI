---
task_id: RLY-1794335293698320
change: fix-initial-relay-race-audit
commit_sha: ce09f8e11a3191e050d0bd95f43a0411dc2923a8
local: passed
local_evidence: "Full core service suite passed 2136 tests with no failures or skips. Full staticAnalysis passed in a fresh single-use daemon after ENOSPC recovery. ci-gates, architecture health, locked Cargo metadata, and independent reviews passed."
remote_ci: not_applicable
remote_ci_evidence: Local source and unit gates own acceptance; hosted workflows are not requested.
device: not_applicable
device_evidence: This repair uses local coroutine and loopback regressions. No physical or external peer acceptance is claimed.
artifact: not_applicable
artifact_evidence: No APK or native binary is published by this repair.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RACE-IDENTITY | RLY-1794335361164579 | InitialRelayRaceRunnerTest: duplicate and blank identity tests; red then green, 2 tests | Passed |
| REQ-RACE-LIVE | RLY-1794335361650684 | InitialRelayRaceRunnerTest: live provisional and exited-during-cleanup regressions; final focused run 72 passed | Passed |
| REQ-RACE-CLEANUP | RLY-1794335362151209 | UpstreamRelaySupervisorTest: failed loser and failed readiness cleanup; original error/cancellation preservation in runner tests | Passed |
| REQ-RACE-NETWORK | RLY-1794335362638457 | BaseServiceRuntimeCoordinatorTest (27) and NetworkHandoverMonitorTest (6), 33 passed; baseline cold-start regression failed with missing handover hash | Passed |
| REQ-RACE-SCOPE | RLY-1794335363122361 | Binding and specification comparison, strict validation and independent review | Passed |

| REQ-TRN-1786264762917886-001 | RLY-1794335363122361 | Production binding and core contract comparison, strict validation and independent review | Passed |
| REQ-TRN-1786264762917886-002 | RLY-1794335363122361 | Production binding and core contract comparison, strict validation and independent review | Passed |
| REQ-TRN-1786264762917886-003 | RLY-1794335363122361 | Production binding and core contract comparison, strict validation and independent review | Passed |
| REQ-TRN-1786264762917886-004 | RLY-1794335363122361 | Production binding and core contract comparison, strict validation and independent review | Passed |
| REQ-TRN-1786264762917886-005 | RLY-1794335363122361 | Production binding and core contract comparison, strict validation and independent review | Passed |
| REQ-TRN-1786264762917886-006 | RLY-1794335363122361 | Production binding and core contract comparison, strict validation and independent review | Passed |
| REQ-RLY-1786707070050078-005 | RLY-1794335362151209 | Failed loser and failed readiness cleanup regressions in final 72-test focused run | Passed |

| REQ-RACE-FRESH-EVIDENCE | RLY-1794335681049757 | Repeated same-scope core and AWG checks; red before isolation, green after; live-runtime evidence tests unchanged | Passed |
| REQ-RACE-RESOLVED-IDENTITY | RLY-1794335683238170 | Kind and profile drift regressions; red before guard and green after; runtime allocation refused | Passed |

## Combined validation

RLY-1794335363643097 owns core service tests, staticAnalysis, ci-gates, architecture health, locked Cargo metadata, independent review, and integration evidence.

## Isolated regression evidence

- F2: worker commit 93bb73ff99, integrated as f9e478e4b7; duplicate and blank candidate tests failed before the guard and passed after it.
- F3: worker commit bcf2bcc9a1, integrated as a59674fd05; cold-start regression failed on original processor/orchestrator. Final 33 tests passed with no failures or skips. Logs: `/tmp/rly17-network-red.log` and `/tmp/rly17-network-final.log`.
- F5: strict OpenSpec and task validation passed. Independent review confirmed that eligibility and captured selector constraints are explicit and the existing integrated gate requirement remains.
- Compatibility with external relay peers and physical device behavior are not inferred from these local tests.

Runtime focused command passed 72 tests with zero failures or skips plus core/service detekt. Log: `/tmp/rly17-runtime-verified.log`. Final reviewed worker tree: `8b13f81e6bbdd4191229a1634d6a4d91519a7da7`.

## Combined-tree gate observations

Tested implementation SHA: `ce09f8e11a3191e050d0bd95f43a0411dc2923a8`.

- Full core service unit task completed: 2,136 tests in 282 classes, zero failures, errors, or skips. XML lives under `core/service/build/test-results/testDebugUnitTest`; summary `/tmp/rly17-service-test-summary.json`.
- Aggregate command `./gradlew :core:service:testDebugUnitTest staticAnalysis -Pripdpi.skipNativeBuild=true --offline --console=plain --max-workers=2` exited 1 after the tests passed. Static analysis hit ENOSPC in app KSP/lint; this is not a passing analysis result. Log `/tmp/rly17-combined-gradle.log`.
- Only this session's disposable worker/audit build outputs were removed; logs and XML evidence were retained. Static analysis retries alone with one worker; the unchanged service tests are not repeated.
- `just ci-gates` passed, including architecture, source budgets, contracts, locale/export parity, and host-owned Rust API snapshots. Log `/tmp/rly17-ci-gates.log`. The runtime-platform snapshot remains Linux-owned as specified by its gate.
- `python3 scripts/ci/check_architecture_health.py` passed: 18 current/baseline indicators, no new or worsened entries. Log `/tmp/rly17-architecture-health.log`.
- `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1` passed: 935 packages, 113 workspace members. Output `/tmp/rly17-cargo-metadata.json`.
- Final independent integration review found no actionable findings. Runtime and network owned paths matched their reviewed worker branches exactly.

## Final analysis result

`./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true --offline --console=plain --max-workers=1 --no-daemon` passed with exit 0 in 6m 7s. Log: `/tmp/rly17-static-analysis-fresh-daemon.log`. The first retry stopped before gate execution on a FileHasher lock held by the previous daemon; the fresh process resolved that state without cache deletion or weakened checks. Source inputs remained unchanged, so the earlier 2,136 passing service tests remain valid.

## Repair commits

- F1: `3309d39f1a` selects only live candidates and publishes after promotion.
- F2: `f9e478e4b7` rejects duplicate and blank profile identities.
- F3: `a59674fd05` preserves cold-start network changes.
- F4: `f7ab75012b` requires completed loser cleanup, including failed readiness.
- F5: `e550c5f106` aligns the current product and core API contracts.
- H1: `ea188eca97` isolates initial health evidence for each runtime, including AWG.
- H2: `ce09f8e11a` rejects resolved race candidate identity drift.

No physical device, real-secret relay, or external peer acceptance was run. These repairs do not claim new native protocol support or complete wire compatibility.
