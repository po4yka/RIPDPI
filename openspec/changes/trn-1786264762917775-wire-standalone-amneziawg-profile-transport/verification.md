---
task_id: TRN-1786264762917775
change: trn-1786264762917775-wire-standalone-amneziawg-profile-transport
commit_sha: 9f9960b2b1ca83ca57cebb7793824524c5a12a20
local: blocked
local_evidence: "3059 Kotlin tests, 93 native tests, 62 network E2E tests and full staticAnalysis passed. A new Hilt test-graph regression was reproduced and fixed; full AndroidTest APK assembly, app detekt and ktlint passed. Unchanged native hotspot and unsafe-boundary baseline failures remain acceptance blockers."
remote_ci: blocked
remote_ci_evidence: "Runtime bundle 0299de9e072a4ac0b784709f7ff10e3ef1726336 passed hosted AWG interop, native builds and APK builds in CI33110649324. Baseline guards and DNS tests failed; a new AndroidTest Hilt binding failure was fixed in9f9960b2b1ca83ca57cebb7793824524c5a12a20 and needs a hosted rerun. No green overall acceptance is claimed."
device: not_applicable
device_evidence: "Acceptance permits independent loopback-peer evidence. No physical-device installation or execution was performed."
artifact: not_applicable
artifact_evidence: "Hosted native builds passed for all four Android ABIs and debug APK builds passed for GitHub, F-Droid and Play. Release verification was still running at this capture. No APK was independently downloaded, installed on a physical device or published as a release."
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
- `:app:compileGithubFullDebugAndroidTestKotlin` passed; local instrumentation was not run.
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
test APK. Hosted emulator verification of the correction remains required.

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

Current [Rust workspace](https://github.com/po4yka/RIPDPI/actions/runs/33110649324/job/98656977628)
and [Rust lint](https://github.com/po4yka/RIPDPI/actions/runs/33110649324/job/98656977653)
logs confirm those same hotspot and Clone-pattern failures respectively.

Current [Android unit tests](https://github.com/po4yka/RIPDPI/actions/runs/33110649324/job/98656977248)
and [Kotlin coverage](https://github.com/po4yka/RIPDPI/actions/runs/33110649324/job/98656977613)
both fail on the same two `ConnectivityDnsTargetPlannerTest` cases:
`resolver audit keeps full resolver matrix` (line 201) and
`generic target expands to diversified candidates` (line 107).
The [baseline Android job](https://github.com/po4yka/RIPDPI/actions/runs/33103005845/job/98630566091)
and [baseline coverage job](https://github.com/po4yka/RIPDPI/actions/runs/33103005845/job/98630566225)
contain identical test names, lines and the result `1408 tests completed, 2 failed`.
This task does not change the diagnostic planner, its tests or these baselines.
