---
task_id: TRN-1793300518162274
change: modernize-native-vless-reality
commit_sha: b232683b6193d052c4e2e35c9309c275c8a36ba8
local: passed
local_evidence: Original scoped gates passed with 6939 JVM tests, 140 Rust library tests, six real Xray cases, Android lint, Kotlin lint and detekt, architecture checks, and independent reviews. The separate SSH repair fd66b33ec resolved the existing app-engine boundary blocker; full staticAnalysis then passed without exclusions or skip-native flags. The repair also passed all 5225 app/service tests and AndroidTest Kotlin compilation.
remote_ci: required
remote_ci_evidence: The SSH, Simple fixture, UI audit, and standalone fuzz blockers are repaired. CodeQL passed all three languages at b232683b6. Full CI 41023398772 found a stale Kaspresso class selector: five of six cases executed on API 37. The selector repair retains all six cases and exact counts; full acceptance of that repair is pending. No gate was weakened or alert dismissed.
device: not_applicable
device_evidence: Host interop and Robolectric layout checks were run. Physical Android VPN and network handover acceptance are not claimed.
artifact: not_applicable
artifact_evidence: No release artifact is owned by this package. The JVM gates use ripdpi.skipNativeBuild=true; they do not establish APK or Android ABI artifact behavior.
deployment: not_applicable
deployment_evidence: No deployment is owned by this package.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-UPSTREAM-INTEROP | TRN-1793300632251317 | e894ae0ed; all six exact cases passed against unmodified Xray 0afec66085a85191e9dd2a4a6646c7c9a94688d1. Each executed one test with zero ignored. Independent Xray-to-Xray control also passed. | passed |
| REQ-MODERN-IMPORT | TRN-1793300631238156 | 5682b1747; flat and legacy forms, password/publicKey equivalence, conflicts, mixed shapes, malformed types, and unsupported options have regression coverage. The old importer failed three new cases. | passed |
| REQ-PRESERVE-REJECTED | TRN-1793300631238156 | 5682b1747 and 577c2368f; rejected manual confirmation and partial URI, Clash, sing-box, WG/AWG refreshes preserve prior groups, credentials, selection and rules. Malformed VLESS UUID/key and unsupported combinations fail full validation before refresh writes. Regression tests failed before the guards were added. | passed |
| REQ-SHARED-CAPABILITIES | TRN-1793300631824762 | 577c2368f; one settings-layer policy governs native import, persistence, editor choices, service admission and UDP support. Tests cover explicit flow choice, retained unknown values, retained UDP intent, empty mode/flow, and lossless generic previews followed by native rejection. | passed |

## Observed local gates

The combined Gradle run completed successfully in 3 minutes 23 seconds. It included the unfiltered unit suites below, all six affected modules' ktlintCheck and detekt tasks, and `:app:lintGithubFullDebug :core:service:lintDebug` with `-Pripdpi.skipNativeBuild=true`.

| Suite | Tests | Failures / errors / skips |
|---|---:|---|
| :core:data:settings:testDebugUnitTest | 59 | 0 / 0 / 0 |
| :core:data:runtime-state:testDebugUnitTest | 374 | 0 / 0 / 0 |
| :core:data:testDebugUnitTest | 965 | 0 / 0 / 0 |
| :core:service:testDebugUnitTest | 1983 | 0 / 0 / 0 |
| :core:engine:testDebugUnitTest | 320 | 0 / 0 / 0 |
| :app:testGithubFullDebugUnitTest | 3238 | 0 / 0 / 0 |
| Total JVM tests | 6939 | 0 / 0 / 0 |

- Guarded `cargo test --locked -p ripdpi-vless --lib`: 140 passed, zero failures and ignored tests.
- `build-gate -- python3 scripts/tests/run-outbound-interop.py --protocol vless --source-dir /tmp/ripdpi-upstream-v26.10.29 --test <case>` ran every registered VLESS case: REALITY TCP 64 KiB, Vision inner TLS 1.3 with fragmented writes, XUDP single-send flush and two targets, invalid key/short ID/UUID, min/max client versions, and stalled/closed peers followed by a fresh successful exchange.
- `python3 -m unittest discover -s scripts/tests -p test_outbound_interop_suite.py`: three tests passed, including zero/ignored selected-test rejection.
- Rust formatting, targeted Clippy, independent general review and async cancel-safety review passed. No actionable review findings remain.
- Architecture health: 21 indicators against 21 baseline entries, zero new/worsened/stale entries. Native architecture contracts: zero violations.
- The translation manifest was regenerated with its owning script; `bash scripts/ci/check-translation-export.sh` passed. The new flow label is present in all ten locales.
- Robolectric readability checks passed at 320/420 dp and font scale 1/2. Existing profile flow stays unchanged until the user selects a supported value.
- Commit hooks remained enabled for each implementation commit.

## Resolved local blocker and hosted acceptance

The original `./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true` failed at `verifyAppEngineBoundary` because the separate SSH editor imported internal probe types. The follow-up SSH repair `fd66b33ec` introduced service-owned observation results and removed those imports. `build-gate --lane ripdpi -- ./gradlew staticAnalysis` then passed in full, including native builds, without changing a rule, baseline, dependency boundary, or allowlist. All 5225 app/service JVM tests and instrumentation Kotlin compilation also passed. The original VLESS interop and Rust results above were not rerun by this follow-up.

The first hosted source run exposed an unrelated stale invocation of a deleted Maestro/Appium selector test. Commit `d675424c8` uses the current ADB driver tests in that step and adds a regression for missing workflow test modules. All existing gate conditions remain in place. [Hosted full static analysis](https://github.com/po4yka/RIPDPI/actions/runs/41009144800/job/123911162076) and [all three CodeQL languages](https://github.com/po4yka/RIPDPI/actions/runs/41009144991) passed at that exact revision, with no open code-scanning alerts.

Hosted Simple tests found twelve seed-fixture failures: omitted TLS+xHTTP mode resolves to unsupported `auto`. The production CI bundle already declares `stream-up`. Commit `b198c6e42` makes both synthetic seed bundles use that supported mode without changing capability checks. All 3405 Simple app tests then passed locally, followed by another full staticAnalysis run. Later [hosted Simple and UI audit tests](https://github.com/po4yka/RIPDPI/actions/runs/41018197242/job/123940246449) passed at `423cc6626`.

Commits `90f4d8f51` and `423cc6626` repair the exact UI audit exclusion check and the standalone fuzz graph. The latter uses public packet APIs and the existing vendored BoringSSL hooks without changing production code or disabling checks. Its [hosted fuzz smoke](https://github.com/po4yka/RIPDPI/actions/runs/41018197242/job/123940246669) passed five input regressions and all eighteen ASan target builds. Both affected fuzz targets also completed 1000 ASan iterations locally.

[CodeQL at b232683b6](https://github.com/po4yka/RIPDPI/actions/runs/41022269149) passed Actions, Rust, and Kotlin/Java. [Full combined-revision CI](https://github.com/po4yka/RIPDPI/actions/runs/41023398772) failed because the API 37 smoke selected an old Home class name and executed five of six cases. The corrected selector still requires all six cases; a new full run is pending. Earlier push runs were cancelled by parallel main updates. Do not archive this change as fully verified until full CI succeeds.

## Scope

Generic URI, sing-box and Clash parsing remains lossless preview. Native VLESS admission rejects unavailable combinations; native refresh also validates endpoint and authentication fields before persistence. The typed libXray provider retains its separate importer and capability checks. No production dependency, JNI, protobuf, wire, storage schema, or release artifact changed.

Local execution logs: `/tmp/vless-package-completion-gates.log`, `/tmp/vless-package-main-interop.log`, `/tmp/vless-package-main-rust.log`, `/tmp/vless-package-main-python.log`, and `/tmp/vless-package-static-completion.log`.
