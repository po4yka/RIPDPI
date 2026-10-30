---
task_id: SVC-1793330036179924
change: decouple-ssh-editor-engine-contracts
commit_sha: b232683b6193d052c4e2e35c9309c275c8a36ba8
local: passed
local_evidence: The unchanged boundary gate and full staticAnalysis passed. All 5225 Full app and service unit tests and all 3405 Simple app tests passed with zero failures, errors, or skips; AndroidTest Kotlin compilation and independent review passed. CI contract repair passed 41 focused tests, the full 279-test script and evidence set, and actionlint. Full staticAnalysis passed again after the Simple fixture repair.
remote_ci: required
remote_ci_evidence: The Simple fixtures, UI audit, and fuzz failures are repaired in b198c6e42, 90f4d8f51, and 423cc6626. Their hosted checks passed before parallel pushes cancelled the overall run. CodeQL passed all three languages at b232683b6. Full CI 41023398772 found a stale Kaspresso class selector: five of six cases executed on API 37. The selector repair retains all six cases and exact counts; full acceptance of that repair is pending. No gate was weakened or alert dismissed.
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
| REQ-VALIDATION | SVC-1793330193449658 | Full staticAnalysis passed locally and in hosted source runs. All CodeQL languages passed at b232683b6; full CI acceptance of the Kaspresso selector repair is pending. | required |

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

[CodeQL](https://github.com/po4yka/RIPDPI/actions/runs/41022269149) passed all three languages at `b232683b6`. The [full manual CI](https://github.com/po4yka/RIPDPI/actions/runs/41023398772) for that exact revision failed because the API 37 smoke selected an old Home class name and executed five of six cases. The corrected selector still requires all six cases; a new full run is pending. Earlier push runs were cancelled by parallel updates to main. Do not archive this change as fully verified until full CI completes successfully.

Source commits: `fd66b33ec` (SSH boundary), `d675424c8` (current UI driver CI contract), `b198c6e42` (supported Simple test fixture mode), `90f4d8f51` (exact UI audit exclusions), and `423cc6626` (standalone fuzz repair).

Local logs: `/tmp/ssh-boundary-static-serial.log`, `/tmp/ssh-boundary-static-final.log`, `/tmp/ssh-boundary-full-tests.log`, `/tmp/ssh-boundary-simple-tests.log`, `/tmp/ssh-boundary-targeted.log`, and `/tmp/ssh-boundary-ci-regression.log`.
