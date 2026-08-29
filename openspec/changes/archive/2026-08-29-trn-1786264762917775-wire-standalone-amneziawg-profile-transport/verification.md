---
task_id: TRN-1786264762917775
change: trn-1786264762917775-wire-standalone-amneziawg-profile-transport
commit_sha: bacc106a665f311b4e0f0708f4bf91a7ae40b6ca
local: passed
local_evidence: "The original 3059 Kotlin, 93 native and 62 network-E2E test gates remain recorded below. Current architecture health passes with 23 current and 23 baseline indicators and zero new, worsened or stale entries, resolving the former closure blocker."
remote_ci: passed
remote_ci_evidence: "Exact-SHA CI run 33251657196 passed all 45 jobs on bacc106a665f311b4e0f0708f4bf91a7ae40b6ca, including network E2E, all Android native ABIs, instrumentation, static analysis, security and release verification."
device: not_applicable
device_evidence: "Acceptance permits independent loopback-peer evidence. No physical-device installation or execution was performed."
artifact: not_applicable
artifact_evidence: "Hosted native builds passed for all four Android ABIs; debug APK builds and release verification passed for GitHub, F-Droid and Play in both CI33115299094 and CI33120994370. Real arm64 artifacts from the former run were consumed by owning prebuilt Gradle tasks to build local Full and Simple debug APKs; APK signatures verified and emulator installation succeeded. No physical-device installation or release publication was performed."
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-TRN-1786264762917775-001 | TRN-1786264762919403 | 87 ripdpi-warp-core unit tests; invalid active codec configuration fails closed | passed |
| REQ-TRN-1786264762917775-002 | TRN-1786264762919688 | Production AWG runtime exchanges encrypted TCP and UDP with pinned independent peer | passed |
| REQ-TRN-1786264762917775-003 | TRN-1786264762919682 | 6 JNI adapter host tests and 6 Kotlin binding/serialization tests passed; hosted native builds passed for all four Android ABIs | passed |
| REQ-TRN-1786264762917775-004 | TRN-1786264762919373 | 6 native configuration serialization tests and 68 editor/DTO tests passed; DNS/routes stay service-owned | passed |
| REQ-TRN-1786264762917775-005 | TRN-1786264762919279 | Standalone activator exact acknowledgement/rollback and stale Start/Stop regressions in 1884 passing service tests | passed |
| REQ-TRN-1786264762917775-006 | TRN-1786264762919408 | 1884 service tests including cold activation, Xray handoff, profile route/DNS/MTU and receipt tests | passed |
| REQ-TRN-1786264762917775-007 | TRN-1786264762919506 | 59 editor tests including permission denial, matching consent and duplicate callback handling | passed |
| REQ-TRN-1786264762917775-008 | TRN-1786264762919526 | Pinned amneziawg-go v0.2.18 peer; real IPv4/IPv6 TCP/UDP, source metadata and stalled-client shutdown passed | passed |

## Closure verification refresh

- `python3 scripts/ci/check_architecture_health.py` passes on the recorded SHA: 23 current indicators, 23 baseline indicators, zero new, worsened or stale entries across 114 crates.
- [CI 33251657196](https://github.com/po4yka/RIPDPI/actions/runs/33251657196) passed all 45 jobs on the exact recorded SHA. This supersedes the historical blocker sections retained below as investigation history.
- The independent pinned `amneziawg-go` loopback evidence remains protocol interoperability proof only; no physical-device or external-VPS connectivity is claimed.

## Reproduction and verification commands

Run compiler-backed commands through `build-gate` on this Mac. Gradle unit and
static checks use `-Pripdpi.skipNativeBuild=true`; that flag does not prove an
Android native artifact. Rust commands use the pinned toolchain and `--locked`.

- `cargo test --locked -p ripdpi-warp-core -p ripdpi-amneziawg-android`: 93 tests passed after regression
  reproduction for bound listener address, UDP source metadata, IPv6 routing,
  TCP/UDP cleanup and runtime shutdown.
- `cargo clippy --locked -p ripdpi-warp-core --all-targets --all-features -- -D warnings` passed.
- The normal native commit hook ran workspace Clippy with `--locked --workspace --no-deps --all-targets -- -D warnings`: passed.
- `RUSTUP_TOOLCHAIN=1.96.0 bash scripts/ci/run-rust-network-e2e.sh`: 25 local fixture tests, 36 proxy E2E tests and the independent AWG interop test passed.
- `python3 scripts/tests/run-standalone-awg-interop.py` passed against the pinned
  independent Go peer; no remote endpoint or device is used.
- `python3 -m unittest discover -s scripts/tests -p test_standalone_awg_interop_runner.py -v`
  passed, including a child process that ignores TERM.
- The final combined Kotlin run passed 3059 tests, with zero failures/errors/skips:
  runtime-state 183, core:data 779, engine-api 55, service 1884,
  selected GithubFull editor/import tests 71 and selected GithubSimple failover tests 87.
- The GithubFull selection includes all 59 AWG editor tests and 12 Xray import
  activation tests. The complete runtime-state suite includes the 9 AWG DTO tests.
- The GithubSimple selection covers `FailoverCoordinatorTest` and
  `SimpleVlessRuntimeMonitorTest`, including suspended preparation and newer-intent races.
- `:core:engine-api:testDebugUnitTest`: 55 passed, including 6 AWG configuration contract tests.
- `:app:compileGithubFullDebugAndroidTestKotlin` passed. Subsequent full test-APK
  assembly and local instrumentation are recorded below.
- The same combined Gradle invocation completed `staticAnalysis`: BUILD SUCCESSFUL,
  809 actionable tasks. No lint, detekt or architecture baseline was extended.
- After fetch/rebase, the combined Kotlin/static-analysis gate passed again on
  `0299de9e072a4ac0b784709f7ff10e3ef1726336` (818 actionable tasks), followed by
  successful integration to main. The 93 native tests and 62 network E2E tests
  also passed again on that exact tree before push.
- Architecture health, runtime boundaries, native architecture contracts and
  async-safety guards passed without baseline changes.
- The final architecture report contains 23 current and 23 baseline indicators,
  with zero new or worsened indicators across 114 crates.

The implementation changes the internal `XrayProviderSelectionStore` getter and
setter from suspending to synchronous operations. Its production implementation
already performs synchronous preferences access; all three test implementations
and every caller are updated. This lets the existing intent arbiter atomically
publish provider selection and enqueue activation without a second lifecycle.

## Hosted implementation checks

For the exact implementation bundle `0299de9e072a4ac0b784709f7ff10e3ef1726336`:

- [Linux network E2E](https://github.com/po4yka/RIPDPI/actions/runs/33110649324/job/98656977722)
  passed. Its log explicitly confirms the independent AWG peer test, real
  handshake, IPv4/IPv6 TCP and UDP source metadata, and stalled-client shutdown.
- Hosted `gradle-static-analysis`, JNI API snapshot, Rust cross-check, Rust
  coverage, native-bloat and Roborazzi checks passed.
- Native builds passed for arm64-v8a, armeabi-v7a, x86 and x86_64. The x86_64
  job uses `x86_64-linux-android` and the owning Gradle native artifact tasks.
- [CodeQL](https://github.com/po4yka/RIPDPI/actions/runs/33110649294),
  [Secret Scan](https://github.com/po4yka/RIPDPI/actions/runs/33110649494),
  dependency graph and fleet-fixtures completed successfully.
- Debug APK builds passed for GitHub, F-Droid and Play. Release verification
  was still running at this capture. No physical-device installation, external
  AWG server test, or release deployment is claimed.

## AndroidTest Hilt regression and correction

The first hosted emulator matrix failed before device tests. The
[API 33 job](https://github.com/po4yka/RIPDPI/actions/runs/33110649324/job/98661334233)
reported four `VpnTransportActivationController` missing bindings in Hilt Java
compilation, followed by four missing generated-component errors. This was a
new regression: the corresponding baseline emulator job passed.

Four MainActivity tests uninstall `ServiceControllerModule`; their recording
controller overrides did not provide the new activation interface. Commit
`9f9960b2b1ca83ca57cebb7793824524c5a12a20` binds that interface to the same existing
recording fake in every affected graph. The fake records the request and target
without real service dispatch. No production source changed in this correction.

The failure was reproduced locally with
`:app:hiltJavaCompileGithubFullDebugAndroidTest`. After the correction,
`:app:assembleGithubFullDebugAndroidTest :app:detekt :app:ktlintCheck` passed
(385 actionable tasks). Compiling only AndroidTest Kotlin had not exercised
Hilt Java generation; future validation of this boundary must assemble the
  test APK. In subsequent [CI33115299094](https://github.com/po4yka/RIPDPI/actions/runs/33115299094),
  all API27/33/35 test graphs compiled and reached device execution. GitHub
  release verification also passed, including Release AndroidTest Hilt compilation.

## Lifecycle test-fixture regression and correction

The same run exposed a second new regression after Hilt compilation was fixed:
the lifecycle test helpers used `ServiceController.stop()` to dispatch a
foreground-service Stop immediately before forced service cleanup. An already
halted service could be recreated by that Stop, then destroyed before foreground
promotion, causing a delayed `ForegroundServiceDidNotStartInTimeException`.
The repeated-stop test's body helper could trigger the same sequence.

Commit `c0e0ec7a1efbe182b982bb010daf1806bdb869ec` restores plain `startService`
Stop dispatch in the test body while retaining the intent-generation stamp.
Cleanup records the accepted user Stop under the existing intent arbiter,
gracefully stops an active runtime with a stamped plain-service intent, waits
for `Halted` outside the arbiter lock, and finally stops any remaining service.
It does not recreate a halted foreground service. Production code, test-method
bodies, assertions and their timeout values are unchanged by this correction.

Both failure sequences were reproduced in real emulator logs. After correction:

- The full integration package on an isolated arm64 API34 emulator produced
  **50 passes, 6 assumption skips, 1 ignored and zero failures/errors**. All
  **24 lifecycle tests passed**; logcat contained no foreground-promotion crash,
  fatal exception or ANR. The runner's `OK (56 tests)` includes the six
  assumption skips and must not be reported as 56 passes.
- The five `StrategyEngineJniInstrumentedTest` tests passed with no skips.
- The Simple-only navigation test passed with no skips using the correct
  `com.poyka.ripdpi.simple.test` instrumentation package. An earlier invocation
  against the Full package produced an assumption skip and is not credited.
- `:app:assembleGithubFullDebugAndroidTest :app:detekt :app:ktlintCheck` passed
  again after fetch/rebase (386 actionable tasks). Architecture health reported
  zero new or worsened indicators; locked Cargo metadata and task validation passed.
- The debug app was assembled using real arm64 native artifacts from exact
  hosted SHA `5428b2bf8e8047a327d64d6d6e7f38b80b44cdda`, through the normal
  prebuilt Gradle tasks, without `skipNativeBuild`. The source changes since
  that SHA affect test fixtures only.

Local evidence files are
`/private/tmp/ripdpi-awg-api34-lifecycle-corrected-20260828-instrumentation.log`,
`/private/tmp/ripdpi-awg-api34-lifecycle-corrected-20260828-logcat.log` and
`/private/tmp/ripdpi-awg-api34-jni-c0e0-20260828-instrumentation.log`.
A read-only reviewer independently verified the counts and lifecycle method list.
These runs use direct AndroidJUnitRunner, not AndroidX Test Orchestrator.
Lifecycle tests retain their existing fake proxy/TUN bindings; they do not prove
real Android AWG peer connectivity. Real protocol interoperability is proved
separately by the independent rootless Go peer test. No physical device was used.

An additional full integration run used the repository-pinned AndroidX Test
Orchestrator 1.6.1 with test-services 1.6.0 and `clearPackageData=true`, following
the [official command-line procedure](https://developer.android.com/training/testing/instrumented-tests/androidx-test-libraries/runner#enable-command).
On the same isolated API34 emulator it completed **50 passes, 6 assumption skips
and zero failures/errors**, including **24/24 lifecycle tests**. The ignored
biometric class is excluded from this Orchestrator collection. Logcat contained
no foreground-promotion crash, fatal exception, fatal signal or ANR. This closes
the local direct-runner isolation gap; it is still emulator fixture evidence,
not physical-device or Android AWG peer evidence.

Additional local logs:
`/private/tmp/ripdpi-awg-api34-simple-correct-package-c0e0-20260828-instrumentation.log`,
`/private/tmp/ripdpi-awg-api34-orchestrator-c0e0-20260828-instrumentation.log` and
`/private/tmp/ripdpi-awg-api34-orchestrator-c0e0-20260828-logcat.log`.

The separate Simple navigation and JNI suites also passed through Orchestrator
with `clearPackageData=true`: one and five passes respectively, without skips.
Their logs are
`/private/tmp/ripdpi-awg-api34-orchestrator-simple-c0e0-20260828-instrumentation.log`
and `/private/tmp/ripdpi-awg-api34-orchestrator-jni-c0e0-20260828-instrumentation.log`.

The local arm64 GitHub Full debug APK is at
`/private/tmp/ripdpi-standalone-awg-profile-20260827/app/build/outputs/apk/githubFull/debug/app-github-full-debug.apk`:
133113712 bytes, SHA256
`02ee81a75c69ec6cfe1a6f29ccbbb02680b85b651451829b0d548af9b7168034`.
`apksigner verify --verbose` passed using APK Signature Scheme v2, and the APK
contains `libripdpi-amneziawg.so` plus the other four repository JNI libraries.
This is a debug artifact; signature verification is not release publication.

## Completed hosted follow-up and current correction

[CI33115299094](https://github.com/po4yka/RIPDPI/actions/runs/33115299094), on exact
SHA `5428b2bf8e8047a327d64d6d6e7f38b80b44cdda`, completed with:

- Passed Linux AWG network E2E, all four Android native ABI builds, JNI API
  snapshot, static analysis, Rust coverage/cross-check, native-bloat and Roborazzi.
- Passed all GitHub, F-Droid and Play debug APK and release verification jobs.
- Passed [CodeQL](https://github.com/po4yka/RIPDPI/actions/runs/33115299099),
  [Secret Scan](https://github.com/po4yka/RIPDPI/actions/runs/33115299093) and fleet fixtures.
- Failed the unchanged native hotspot/Clone guards and the same two diagnostic
  DNS planner tests described below. API27/33/35 instrumentation failed on the
  newly exposed lifecycle fixture issue; that issue was not classified as baseline.

The fixture correction was pushed to `main` as
`c0e0ec7a1efbe182b982bb010daf1806bdb869ec`; remote SHA was confirmed.
[CI33120994370](https://github.com/po4yka/RIPDPI/actions/runs/33120994370) completed
with 23 successful, 31 skipped and five failed jobs. Skipped jobs are not credited
as passes. Rust workspace/lint/network E2E jobs were skipped for this test-only
correction; the prior runtime-source verification remains the relevant evidence.

All four native ABI builds, static analysis, JNI API snapshot, Roborazzi, all
three debug APK jobs and all three release-verification jobs passed. The full
instrumentation matrix passed, including each job's required-result validators.
A read-only reviewer independently downloaded the artifacts in memory and
verified the preserved JUnit XML, matching declared/actual counts without duplicates:

| API | Job | Artifact ID | Full PASS / skip / failure / error | Lifecycle | Simple | JNI |
|---|---|---|---|---|---|---|
| 27 | [98693284949](https://github.com/po4yka/RIPDPI/actions/runs/33120994370/job/98693284949) | 9667588231 | 50 / 6 / 0 / 0 | 24/24 passed | 1/1 passed, no skips | Not scheduled |
| 33 | [98693284868](https://github.com/po4yka/RIPDPI/actions/runs/33120994370/job/98693284868) | 9667631964 | 50 / 6 / 0 / 0 | 24/24 passed | 1/1 passed, no skips | Not scheduled |
| 35 | [98693284902](https://github.com/po4yka/RIPDPI/actions/runs/33120994370/job/98693284902) | 9667663443 | 50 / 6 / 0 / 0 | 24/24 passed | 1/1 passed, no skips | 5/5 passed, no skips |

The six Full skips per API are the ECH/DoQ/H3 network probes, API37 NSC,
root-helper opt-in, and Simple-only test. The last test passed separately in
the Simple variant on every API. Existing fake lifecycle bindings remain in use;
these results do not establish physical-device or Android AWG peer connectivity.

[CodeQL](https://github.com/po4yka/RIPDPI/actions/runs/33120994367),
[Secret Scan](https://github.com/po4yka/RIPDPI/actions/runs/33120994366) and
[fleet-fixtures](https://github.com/po4yka/RIPDPI/actions/runs/33120994377) also
passed on the exact correction SHA.

The remaining failures are the unchanged
[hotspot guard](https://github.com/po4yka/RIPDPI/actions/runs/33120994370/job/98687569574)
(`listener.rs`, 72 > 54), the same two DNS planner cases in
[unit tests](https://github.com/po4yka/RIPDPI/actions/runs/33120994370/job/98690815018)
and [coverage](https://github.com/po4yka/RIPDPI/actions/runs/33120994370/job/98690815317),
and their preflight/required-check aggregates. Both DNS logs report
`1408 tests completed, 2 failed`, at the unchanged lines 201 and 107.
The task remains in review; no baseline, assertion, security check or quality
gate was weakened to obtain these results.

## Existing baseline failures

The implementation bundle was pushed to main and its exact remote SHA was
confirmed. [Hosted CI run 33110649324](https://github.com/po4yka/RIPDPI/actions/runs/33110649324)
passed the task/OpenSpec contract gate; its
[architecture-health job](https://github.com/po4yka/RIPDPI/actions/runs/33110649324/job/98652343619)
failed at `Run native hotspot budgets`. The same guard rerun locally on the
published tree reported exactly one over-budget file: `listener.rs`, 72 > 54.
The hosted job log download timed out; the job/step conclusion was retrieved
separately from the Actions API. Other jobs were still running at capture.
GitHub accepted the requested direct main push with the existing bypass
authority; that acceptance is not evidence that required checks passed.

Baseline main `7d8580c92dc6f011a4e685d0677e87a59469c248` has failing
[CI jobs](https://github.com/po4yka/RIPDPI/actions/runs/33103005845).
The native hotspot guard still reports `ripdpi-tunnel-core/src/io_loop/tcp_accept/listener.rs`
72 lines against a 54-line limit. The unsafe-boundary guard reports the existing
Clone owner pattern in `ripdpi-flow-app-attribution/src/lib.rs:160`. Neither file
nor its baseline is changed by this task. These failures remain visible and do
not count as passed gates.

The earlier implementation [Rust workspace](https://github.com/po4yka/RIPDPI/actions/runs/33110649324/job/98656977628)
and [Rust lint](https://github.com/po4yka/RIPDPI/actions/runs/33110649324/job/98656977653)
logs confirm those same hotspot and Clone-pattern failures respectively.

The earlier implementation [Android unit tests](https://github.com/po4yka/RIPDPI/actions/runs/33110649324/job/98656977248)
and [Kotlin coverage](https://github.com/po4yka/RIPDPI/actions/runs/33110649324/job/98656977613)
both fail on the same two `ConnectivityDnsTargetPlannerTest` cases:
`resolver audit keeps full resolver matrix` (line 201) and
`generic target expands to diversified candidates` (line 107).
The [baseline Android job](https://github.com/po4yka/RIPDPI/actions/runs/33103005845/job/98630566091)
and [baseline coverage job](https://github.com/po4yka/RIPDPI/actions/runs/33103005845/job/98630566225)
contain identical test names, lines and the result `1408 tests completed, 2 failed`.
This task does not change the diagnostic planner, its tests or these baselines.
