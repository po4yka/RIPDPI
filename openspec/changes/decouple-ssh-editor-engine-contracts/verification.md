---
task_id: SVC-1793330036179924
change: decouple-ssh-editor-engine-contracts
commit_sha: 90f151afb1871c484ee184aa7d5159dc0e514402
local: passed
local_evidence: The unchanged boundary gate and full staticAnalysis passed. All 5225 Full app and service unit tests and all 3405 Simple app tests passed with zero failures, errors, or skips; AndroidTest Kotlin compilation and independent review passed. CI contract repair passed 41 focused tests, the full 279-test script and evidence set, and actionlint. Full staticAnalysis passed again after the Simple fixture repair.
remote_ci: passed
remote_ci_evidence: Full CI 41039550816 passed all 50 jobs at 90f151afb1871c484ee184aa7d5159dc0e514402, including staticAnalysis, UI audit, fuzz, upstream interop, all native ABIs, debug/release builds, and all five Android APIs. API 37 executed all six smoke cases with zero failures, errors, or skips. CodeQL 41039363672 passed all three languages with no extraction warnings or errors and zero open alerts. No gate was weakened.
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
| REQ-VALIDATION | SVC-1793330193449658 | Full staticAnalysis passed locally. Full hosted CI passed all 50 jobs at 90f151afb, including all five Android APIs; all three CodeQL languages passed with zero open alerts. | passed |

## Observed local checks

- The unchanged `verifyAppEngineBoundary` reproduced five forbidden imports before the repair and passed after it. No dependency, allowlist, baseline, or boundary rule changed.
- `build-gate --lane ripdpi -- ./gradlew staticAnalysis` passed in 2 minutes 8 seconds with no task exclusions and no skip-native property. Full and Simple checks and native builds ran. An earlier concurrent native build failed during BoringSSL header generation; the serialized full run passed without source or gate changes.
- `./gradlew :core:service:testDebugUnitTest :app:testGithubFullDebugUnitTest :app:compileGithubFullDebugAndroidTestKotlin` passed in 5 minutes 36 seconds. Service: 1987 tests; app: 3238 tests; zero failures, errors, or skips. This run used real native builds. Instrumentation sources compiled; device execution is not claimed.
- The targeted observer, operation-runner, and SSH editor unit tests passed before the full suites. Formatting passed for app and service.
- Architecture health passed with 21 indicators against 21 baseline entries and zero new, worsened, or stale entries. Native architecture contracts had zero violations.
- An independent read-only review found no actionable defect. The existing runner, DNS, socket protection, native calls, and cleanup remain unchanged.
- The CI follow-up regression failed on the deleted `test_home_disclosure_selector_contract` invocation before its replacement. All 41 CI/tool/ADB driver tests and all 279 tests from the affected workflow step passed afterward. `actionlint .github/workflows/ci.yml` and `git diff --check` passed. No job dependency, success condition, timeout, or required gate changed.

- Hosted Simple tests found 12 failures in `ConfigSeederTest` because its synthetic TLS+xHTTP bundles omitted a mode and therefore selected unsupported `auto`. Commit `b198c6e42` explicitly uses supported `stream-up`, as the checked-in CI bundle already does. It changes only two test fixture strings. No capability or validation code changed. The full `:app:testGithubSimpleDebugUnitTest` suite then passed locally: 3405 tests, zero failures, errors, or skips. A second full `build-gate --lane ripdpi -- ./gradlew staticAnalysis` passed in 1 minute 40 seconds after that edit.

## Historical hosted evidence

- [CI at d675424c8](https://github.com/po4yka/RIPDPI/actions/runs/41009144800): [gradle-static-analysis](https://github.com/po4yka/RIPDPI/actions/runs/41009144800/job/123911162076) completed successfully, including the actual static analysis step. Architecture, preflight, all native ABI producers, Rust lint/workspace tests/coverage/Miri/Loom, Kotlin coverage, Roborazzi, and real-Xray linked engine/service tests also passed. Remaining build jobs were still running when evidence was recorded; overall success is not claimed.
- [CodeQL at d675424c8](https://github.com/po4yka/RIPDPI/actions/runs/41009144991) completed successfully for Actions, Rust, and Kotlin/Java. The code-scanning analyses API reported that exact SHA with no extraction errors or warnings. The open-alert API returned zero alerts; existing dismissed alerts were not changed.
- [Simple app tests](https://github.com/po4yka/RIPDPI/actions/runs/41009144800/job/123911533002) exposed the fixture issue repaired and fully tested locally in `b198c6e42`. Later [hosted Simple and UI audit tests](https://github.com/po4yka/RIPDPI/actions/runs/41018197242/job/123940246449) passed at `423cc6626`.
- [rust-fuzz-smoke](https://github.com/po4yka/RIPDPI/actions/runs/41009144800/job/123911533221) failed with E0425 because four packet helpers were unavailable in a normal dependency build. Commit `423cc6626` uses public mutation APIs, preserves malformed TLS record inputs, and shares the main workspace's vendored BoringSSL hooks. Its [hosted fuzz smoke](https://github.com/po4yka/RIPDPI/actions/runs/41018197242/job/123940246669) passed five regression tests and all eighteen ASan target builds.

## Combined acceptance

[Full CI](https://github.com/po4yka/RIPDPI/actions/runs/41039550816) passed all 50 jobs at `90f151afb`, including staticAnalysis, UI audit, fuzz, real upstream interop, native/Android builds, all five Android APIs, and ci-required. [CodeQL](https://github.com/po4yka/RIPDPI/actions/runs/41039363672) passed all three languages on the same revision, with no extraction errors or warnings and zero open alerts.

The full follow-up found and repaired the old Home smoke selector (`87eab4287`), the moved exact async exception binding (`c7bec985b`), and API 37 guest memory pressure (`a04fd44a6`). Its [API 37 result](https://github.com/po4yka/RIPDPI/actions/runs/41039550816/job/124023427476) contains all six smoke cases with zero failures, errors, or skips. No scanner, baseline, count, skip guard, or test assertion was reduced. Earlier cancelled runs are not acceptance evidence.

Source commits: `fd66b33ec` (SSH boundary), `d675424c8` (current UI driver CI contract), `b198c6e42` (supported Simple test fixture mode), `90f4d8f51` (exact UI audit exclusions), and `423cc6626` (standalone fuzz repair).

Local logs: `/tmp/ssh-boundary-static-serial.log`, `/tmp/ssh-boundary-static-final.log`, `/tmp/ssh-boundary-full-tests.log`, `/tmp/ssh-boundary-simple-tests.log`, `/tmp/ssh-boundary-targeted.log`, and `/tmp/ssh-boundary-ci-regression.log`.
