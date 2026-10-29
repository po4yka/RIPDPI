---
task_id: TRN-1793300518162274
change: modernize-native-vless-reality
commit_sha: 577c2368fc8a35487988fad8ecdb31ff032302f7
local: blocked
local_evidence: All scoped gates passed with 6939 JVM tests, 140 Rust library tests, six real Xray cases, Android lint, Kotlin lint and detekt, architecture checks, and independent reviews. Full staticAnalysis is blocked only by existing SSH app-engine boundary imports from dba366973; those files are unchanged by this package.
remote_ci: required
remote_ci_evidence: The three separate implementation commits are on main and pushed to origin. Hosted CI and CodeQL acceptance remain pending.
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

## Unresolved gate

`./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true` failed at `verifyAppEngineBoundary` only. The SSH editor imports internal `SshHostKeyProbeFailure` / `SshHostKeyProbeResult` types in `SshHostKeyFetch.kt`, `SshProfileViewModel.kt`, and `SshProfileViewModelTest.kt`. These files came from the separate committed SSH work and are unchanged by the three implementation commits. No rule, baseline, or dependency boundary was weakened. The local category remains blocked and this change must not be archived as fully verified until that gate and hosted CI are resolved.

## Scope

Generic URI, sing-box and Clash parsing remains lossless preview. Native VLESS admission rejects unavailable combinations; native refresh also validates endpoint and authentication fields before persistence. The typed libXray provider retains its separate importer and capability checks. No production dependency, JNI, protobuf, wire, storage schema, or release artifact changed.

Local execution logs: `/tmp/vless-package-completion-gates.log`, `/tmp/vless-package-main-interop.log`, `/tmp/vless-package-main-rust.log`, `/tmp/vless-package-main-python.log`, and `/tmp/vless-package-static-completion.log`.
