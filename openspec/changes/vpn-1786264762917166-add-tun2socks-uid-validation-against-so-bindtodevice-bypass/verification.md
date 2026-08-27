---
task_id: VPN-1786264762917166
change: vpn-1786264762917166-add-tun2socks-uid-validation-against-so-bindtodevice-bypass
commit_sha: 943b29fefe2f77a6805d966b29bf0241ef5ad80d
local: passed
local_evidence: 338 native tests, 2148 service/engine JVM tests, 102 Python tests, Clippy, instrumentation compilation, staticAnalysis, architecture, task contracts, arm64 native build and ELF checks passed; privileged/device tests remain separate.
remote_ci: required
remote_ci_evidence: Awaiting automatic CI on the new remote source revision; historical CI does not validate this change.
device: blocked
device_evidence: No devices in adb devices -l on 2026-08-27; source-bound kernel >=5.7 and <5.7 physical runs and socket-table evidence remain required.
artifact: not_applicable
artifact_evidence: No distributable artifact is required for this portfolio area.
deployment: not_applicable
deployment_evidence: RIPDPI changes are not deployed by the task workflow.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPN-1786264762917166-001 | VPN-1786264762917376 | Source audit confirms smoltcp UID seams and newly corrected raw-egress/lifecycle gaps | passed |
| REQ-VPN-1786264762917166-002 | VPN-1786264762917494 | 338 affected-crate host tests, Clippy, generation/ownership regression cases; physical run separate | passed |
| REQ-VPN-1786264762917166-003 | VPN-1786264762917458 | Real separate-UID Android and Linux harnesses exist; current-source physical execution unavailable | blocked |
| REQ-VPN-1786264762917166-004 | VPN-1786264762917653 | v4 proc observer and strict evidence mutation tests implemented; no adb device available | blocked |
| REQ-VPN-1786264762917166-005 | VPN-1786266573979046 | TCP/UDP/ICMP/MapDNS local regressions pass; current-source physical protocol evidence missing | blocked |
| REQ-VPN-1786264762917166-006 | VPN-1786266573979750 | Actual kernel-band and runtime-state qualification implemented; both Android device runs missing | blocked |

## Local verification and remaining acceptance

- Rust commands use the pinned toolchain from `native/rust` and a worktree-local `CARGO_TARGET_DIR`; a shared target produced a zero-test result during development and that invocation is not evidence.
- `cargo nextest run --locked --jobs 3 -p ripdpi-tunnel-core -p ripdpi-flow-app-attribution --all-features --no-fail-fast`: 338 passed; the pre-existing `quic_handshake_and_echo_round_trip_through_udp_session_relay` test is ignored as timing-sensitive. The Linux-only privileged TUN targets are separately excluded by platform configuration on this macOS host. `cargo clippy` with both crates, all targets/features, and `-D warnings` passed.
- `python3 -m unittest scripts.tests.test_android_so_bind_physical_evidence scripts.tests.test_so_bindtodevice_evidence scripts.tests.test_so_bindtodevice_lane`: 102 passed. `shellcheck -x` and `bash -n` passed for the physical runner.
- `:core:service:testDebugUnitTest` and `:core:engine:testDebugUnitTest`: 1852 + 296 passed, no skipped tests. Instrumentation compilation is compile-only evidence, not a physical APK run.
- Combined Gradle gate `:core:service:testDebugUnitTest :core:engine:testDebugUnitTest :app:compileGithubFullDebugAndroidTestKotlin staticAnalysis -Pripdpi.skipNativeBuild=true --offline --max-workers=4 --no-watch-fs --no-configuration-cache --console=plain`: BUILD SUCCESSFUL. The skip-native flag applies only to this JVM/lint/compile gate.
- The native implementation commit also passed the repository pre-commit Clippy gate for the complete workspace (`--workspace --no-deps --all-targets -- -D warnings`), native architecture contracts, and format checks.
- Android arm64 build: `build-gate -- env -u CARGO_BUILD_JOBS ./gradlew :core:engine:buildRustNativeLibs -Pripdpi.nativeAbisOverride=arm64-v8a -Pripdpi.nativeCpuBudget=2 -Pripdpi.nativeAbiParallelism=1 --offline --max-workers=3 --no-watch-fs --no-configuration-cache --console=plain` passed for all five JNI libraries in `android-jni-dev`. The ambient jobs variable was removed because the Gradle task rejects external Cargo overrides; the machine gate and two-job Gradle budget remained active.
- `python3 scripts/ci/verify_native_elfs.py --lib-dir core/engine/build/generated/jniLibs --abis arm64-v8a` passed: required libraries, dependency allowlists, >=16 KiB LOAD alignment, and exported-function allowlist. This is one-ABI local build evidence, not a release APK or installed-device test.
- Read-only native and Android reviews found and then verified fixes for TCP listener ownership and detached runtime qualification. No dependency, locale, golden, protobuf, or JNI method-signature changes were required.
- Android physical runner: `scripts/ci/run-android-so-bind-physical-e2e.sh`, with `RIPDPI_SO_BIND_EVIDENCE_PROFILE=physical_kernel_ge57` and `physical_kernel_lt57` on the respective devices. A reachable owner-controlled fixture and permission to run VPN/network probes are prerequisites. The strict validator requires matching source/APK hashes and a fresh nonce. Do not substitute earlier Pixel or Linux manifests.
- Socket-table evidence is sampled, with positive-control visibility required in every sample and at least three samples during the denial window. It cannot establish continuous absence of arbitrarily short-lived sockets.
