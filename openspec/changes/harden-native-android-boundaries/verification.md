---
task_id: AND-1793270044084741
change: harden-native-android-boundaries
commit_sha: 81a31e2af9ca0247803c05a3ae9b3193eb47b8af
local: passed
local_evidence: Scoped Rust tests and Clippy, 6861 Android JVM tests, final import regressions, staticAnalysis, architecture health and locked Cargo metadata passed. See commands and limits below.
remote_ci: required
remote_ci_evidence: Remote CI has not been observed for the final main commit.
device: passed
device_evidence: All five ImportFileDescriptorInstrumentedTest cases passed with no skips on a fresh leased API 37 arm64 AVD after normal app initialization. The wrapper removed the temporary AVD.
artifact: passed
artifact_evidence: Github Full debug and instrumentation APKs built with the actual arm64 native libraries and helpers. No release APK acceptance is claimed.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Findings and fixes

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-HELPER-AUTH | AND-1793270178889694 | NaiveProxy 40 tests; service suite; independent PR and async review | passed |
| REQ-LEGACY-UDP | AND-1793270179448859 | Native rejection before DNS, profile capability and full service resolver regressions; TCP remains supported | passed |
| REQ-SS2022-BOUND | AND-1793270180143531 | Two session slots; strict 61-second retention; replay, timestamp rounding and invalid-packet regressions for three ciphers | passed |
| REQ-IMPORT-BOUND | AND-1793270180814944 | Bounded provider admission and cancellation, Activity lifetime tests; all five real descriptor tests passed | passed |
| REQ-RELEASE-READ | AND-1793270181548518 | Linux debug and release completion regressions; independent PR and unsafe-code review | passed |

Helper listeners that use stored configuration now require session credentials. NaiveProxy checks both SOCKS and HTTP CONNECT clients before upstream access. The service supplies credentials to helper readiness checks, native wrappers and the fixed-target pluggable-transport bridge. Credentials stay out of command arguments and logs.

Legacy Shadowsocks UDP code was removed. Import mapping and service resolution require an SS2022 method for UDP; native admission also rejects legacy UDP before it resolves an endpoint. UI projection carries the validated UDP flag without copying credentials into UI configuration. Legacy TCP remains supported.

SS2022 retains at most two replay windows. A previous session cannot be replaced until it has been inactive for strictly more than 61 seconds. The extra second covers the inclusive timestamp window and whole-second timestamp precision. Authentication, recipient, type and freshness checks precede state changes. Rejected packets cannot evict or refresh a session.

Shared imports admit at most two readers and have no request queue. Provider cancellation and descriptor closure have separate bounded workers. FileChannel closure interrupts a blocked pipe read; the deadline task is removed when the read ends. The reader preserves asset offset and length, limits input to 64 KiB, and cannot deliver a result after Activity destruction. The framework contracts are documented in [FileChannel](https://developer.android.com/reference/java/nio/channels/FileChannel) and [AssetFileDescriptor](https://developer.android.com/reference/android/content/res/AssetFileDescriptor).

Fixed-read completion sets the buffer length outside debug assertions. EOF also clears the length. The two regressions failed on the original optimized code and passed after the fix.

Two additional build defects were fixed: the existing relay future layout needed a recursion limit of 256, and unused TUN test helpers caused Linux Clippy failures. The original relay code reproduced the layout failure. TUN helpers now live in the test module; their tests remain intact. No lint rule, threshold or baseline was relaxed.

## Observed local checks

- `cargo test -p ripdpi-naiveproxy --locked`: 40 passed. All-target Clippy with warnings denied passed.
- `cargo test -p ripdpi-shadowsocks -p ripdpi-relay-tls-transports -p ripdpi-relay-core -p local-network-fixture --locked`: 353 passed, two existing external-peer tests ignored. Scoped Clippy and commit hooks passed.
- Linux `cargo test -p ripdpi-io-uring --locked --lib` in debug and release: 28 reported passed in each profile. The two fixed-read regressions execute without kernel io_uring support. Linux all-target Clippy with warnings denied passed.
- Full JVM suites: app 3215, service 2003, data 975, runtime-state 364, engine 304; no failures, errors or skips. After the final descriptor compatibility change, all 14 import host tests passed again.
- `./gradlew staticAnalysis -Pripdpi.skipNativeBuild=true`: passed, including Detekt, ktlint, Android Lint, file limits and module boundaries.
- `./gradlew :core:engine:preBuild -Pripdpi.localNativeAbis=arm64-v8a`: passed with normal native wiring.
- `./gradlew :app:assembleGithubFullDebug :app:assembleGithubFullDebugAndroidTest -Pripdpi.localNativeAbis=arm64-v8a`: passed. The existing verified Xray AAR was supplied through the supported prebuilt input. Native build skipping was used only for host checks, never APK assembly.
- `python3 scripts/ci/check_architecture_health.py`: 21 existing indicators, no new, worsened or stale entries.
- `cargo metadata --manifest-path native/rust/Cargo.toml --locked`: passed; 114 workspace members.

- `:app:connectedGithubFullDebugAndroidTest` for `ImportFileDescriptorInstrumentedTest`: five passed, no failures, errors or skips on API 37 arm64. The existing pool slot lacked the app data directory even after reinstall. A fresh temporary AVD with normal app initialization passed the same tests with Orchestrator data clearing enabled; the wrapper removed that AVD.

## Atomic commits

| Fix | Commit |
|---|---|
| Relay future layout build limit | [e28e8a300](https://github.com/po4yka/RIPDPI/commit/e28e8a3007cea9d6ad69092f5317a926817741e7) |
| Linux TUN Clippy build fix | [a496373f0](https://github.com/po4yka/RIPDPI/commit/a496373f079862fb6392980ac476ad372e3e1eb3) |
| Release fixed-read length | [f585d85a6](https://github.com/po4yka/RIPDPI/commit/f585d85a63830395b259394c3007e9ec8161dc87) |
| Helper frontend authentication | [130f8e06b](https://github.com/po4yka/RIPDPI/commit/130f8e06b6246840001acba016e7525bee36d949) |
| Legacy Shadowsocks UDP removal | [4db1d03be](https://github.com/po4yka/RIPDPI/commit/4db1d03be39ffbbc5b63657aeb050b1ca125151d) |
| SS2022 replay state bound | [c365434b8](https://github.com/po4yka/RIPDPI/commit/c365434b858aeb11f6f4a55acc5047f696ef863a) |
| Bounded and cancellable content import | [81a31e2af](https://github.com/po4yka/RIPDPI/commit/81a31e2af9ca0247803c05a3ae9b3193eb47b8af) |

Publication grouped each complete fix into one commit. A Git diff confirmed that the Kotlin and Rust implementation files match the tested source. The newer main commits changed CI, scripts and documentation only; native architecture contracts and all 11 app-routing script tests passed after rebase. The original work branches remain available.

## Acceptance limits

A provider that never returns from open can occupy the two reader slots until the process exits. Further imports are rejected without an unbounded queue or thread growth. Raw upstream pluggable-transport listeners that accept caller-supplied configuration are outside the stored-credential frontend requirement.

The Linux container cannot exercise the real io_uring driver: 12 kernel-dependent tests return early in each profile. This does not skip the new completion regressions. The two ignored relay tests require external Mieru and Hysteria peer runners. No physical-device, full release APK, other-ABI, Miri, deployment or remote-CI acceptance is claimed.
