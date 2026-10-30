---
task_id: SVC-1793330036179924
change: decouple-ssh-editor-engine-contracts
commit_sha: b198c6e4296fedd76d3f7392651f482399a166f6
local: passed
local_evidence: The unchanged boundary gate and full staticAnalysis passed. All 5225 Full app and service unit tests and all 3405 Simple app tests passed with zero failures, errors, or skips; AndroidTest Kotlin compilation and independent review passed. CI contract repair passed 41 focused tests, the full 279-test script and evidence set, and actionlint. Full staticAnalysis passed again after the Simple fixture repair.
remote_ci: blocked
remote_ci_evidence: At d675424c8, hosted gradle-static-analysis and all three CodeQL languages passed. Open code-scanning alerts returned an empty list. The CI run exposed unsupported auto mode in Simple seed fixtures, repaired in b198c6e42 and verified locally, plus an independent rust-fuzz-smoke compile failure from four test-only ripdpi_packets APIs. Overall CI acceptance remains blocked; no gate was weakened or result dismissed.
device: not_applicable
device_evidence: No device or VPN acceptance is owned by this in-process contract repair.
artifact: not_applicable
artifact_evidence: No release artifact is owned by this repair.
deployment: not_applicable
deployment_evidence: No deployment is owned by this repair.
---

# Verification

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-SERVICE-BOUNDARY | SVC-1793330193449658 | fd66b33ec; service-owned result types and unchanged verifyAppEngineBoundary passed. | passed |
| REQ-OBSERVATION-PARITY | SVC-1793330193449658 | fd66b33ec; all native and service failure meanings, success data, cancellation, and editor trust tests passed. | passed |
| REQ-VALIDATION | SVC-1793330193449658 | Full staticAnalysis passed locally and on hosted d675424c8; all CodeQL languages passed. Overall CI remains blocked by the separate fuzz-target compile failure. | blocked |

## Observed local checks

- The unchanged `verifyAppEngineBoundary` reproduced five forbidden imports before the repair and passed after it. No dependency, allowlist, baseline, or boundary rule changed.
- `build-gate --lane ripdpi -- ./gradlew staticAnalysis` passed in 2 minutes 8 seconds with no task exclusions and no skip-native property. Full and Simple checks and native builds ran. An earlier concurrent native build failed during BoringSSL header generation; the serialized full run passed without source or gate changes.
- `./gradlew :core:service:testDebugUnitTest :app:testGithubFullDebugUnitTest :app:compileGithubFullDebugAndroidTestKotlin` passed in 5 minutes 36 seconds. Service: 1987 tests; app: 3238 tests; zero failures, errors, or skips. This run used real native builds. Instrumentation sources compiled; device execution is not claimed.
- The targeted observer, operation-runner, and SSH editor unit tests passed before the full suites. Formatting passed for app and service.
- Architecture health passed with 21 indicators against 21 baseline entries and zero new, worsened, or stale entries. Native architecture contracts had zero violations.
- An independent read-only review found no actionable defect. The existing runner, DNS, socket protection, native calls, and cleanup remain unchanged.
- The CI follow-up regression failed on the deleted `test_home_disclosure_selector_contract` invocation before its replacement. All 41 CI/tool/ADB driver tests and all 279 tests from the affected workflow step passed afterward. `actionlint .github/workflows/ci.yml` and `git diff --check` passed. No job dependency, success condition, timeout, or required gate changed.

- Hosted Simple tests found 12 failures in `ConfigSeederTest` because its synthetic TLS+xHTTP bundles omitted a mode and therefore selected unsupported `auto`. Commit `b198c6e42` explicitly uses supported `stream-up`, as the checked-in CI bundle already does. It changes only two test fixture strings. No capability or validation code changed. The full `:app:testGithubSimpleDebugUnitTest` suite then passed locally: 3405 tests, zero failures, errors, or skips. A second full `build-gate --lane ripdpi -- ./gradlew staticAnalysis` passed in 1 minute 40 seconds after that edit.

## Hosted evidence and remaining gap

- [CI at d675424c8](https://github.com/po4yka/RIPDPI/actions/runs/41009144800): [gradle-static-analysis](https://github.com/po4yka/RIPDPI/actions/runs/41009144800/job/123911162076) completed successfully, including the actual static analysis step. Architecture, preflight, all native ABI producers, Rust lint/workspace tests/coverage/Miri/Loom, Kotlin coverage, Roborazzi, and real-Xray linked engine/service tests also passed. Remaining build jobs were still running when evidence was recorded; overall success is not claimed.
- [CodeQL at d675424c8](https://github.com/po4yka/RIPDPI/actions/runs/41009144991) completed successfully for Actions, Rust, and Kotlin/Java. The code-scanning analyses API reported that exact SHA with no extraction errors or warnings. The open-alert API returned zero alerts; existing dismissed alerts were not changed.
- [Simple app tests](https://github.com/po4yka/RIPDPI/actions/runs/41009144800/job/123911533002) exposed the fixture issue repaired and fully tested locally in `b198c6e42`. Hosted results for this test-only follow-up are not claimed.
- [rust-fuzz-smoke](https://github.com/po4yka/RIPDPI/actions/runs/41009144800/job/123911533221) failed with E0425 in `native/rust/fuzz/fuzz_targets/packets_tls_quic.rs` at lines 19, 20, 21, and 81. The target calls `randomize_tls_seeded_like_c`, `randomize_tls_sni_seeded_like_c`, `duplicate_tls_session_id_like_c`, and `part_tls_like_c`, which are unavailable in a normal dependency build. This separate Rust fuzz-harness repair is outside the SSH API change. Do not archive this change as fully verified while the hosted category is blocked.

Source commits: `fd66b33ec` (SSH boundary), `d675424c8` (current UI driver CI contract), and `b198c6e42` (supported Simple test fixture mode). The last commit changes no production input to the completed CodeQL run.

Local logs: `/tmp/ssh-boundary-static-serial.log`, `/tmp/ssh-boundary-static-final.log`, `/tmp/ssh-boundary-full-tests.log`, `/tmp/ssh-boundary-simple-tests.log`, `/tmp/ssh-boundary-targeted.log`, and `/tmp/ssh-boundary-ci-regression.log`.
