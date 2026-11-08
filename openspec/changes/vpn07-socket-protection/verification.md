---
task_id: VPN-1794152756242597
change: vpn07-socket-protection
commit_sha: 145387cae23bd1e8d39ac35eda3f9f016d0ebee8
local: passed
local_evidence: Targeted Rust and Kotlin regressions, Linux UDS timeout tests, Clippy, ktlint, detekt, locked metadata, cargo deny and combined source gates passed. Independent source review passed.
remote_ci: required
remote_ci_evidence: CI run 42504685684 is still in progress for this SHA. CodeQL and the five other guard workflows passed. No failed CI job was observed at receipt time.
device: passed
device_evidence: Five instrumented tests passed on an owned API 37 arm64-v8a temporary emulator with shell UID 2000. The source SHA and clean status stayed unchanged. The temporary AVD was removed.
artifact: passed
artifact_evidence: Real arm64 native libraries, app APK and test APK built without skipNativeBuild on the recorded SHA. APK and test APK hashes are below. Other local ABI splits are not native-build evidence.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-VPN07-PROXY | VPN-1794152886426471 | UI and CLI context conversion; kernel TCP accept denial and UDP connect rejection; inactive mode remains available | passed |
| REQ-VPN07-UDS-BOUNDS | VPN-1794152888395240 | Two Linux kernel accept-queue saturation tests returned errors in 1.04 s and 1.02 s; existing ownership tests passed | passed |
| REQ-VPN07-CLEANUP | VPN-1794152890137023 | Real host resources stay owned on pending stop, cancellation or error; explicit retry releases them; service revoke preserves PermissionLost | passed |
| REQ-VPN07-PROFILE-CHECK | VPN-1794152891543030 | Host subprocess tests check active, inactive, missing and stale protection paths; this is wiring evidence, not Android Naive interoperability | passed |
| REQ-VPN07-UDP-ORDER | VPN-1794152892651101 | Six factory regressions inspect kernel local ports before bind; default parallel Rust tests pass | passed |
| REQ-VPN07-SERVER-BOUNDS | VPN-1794152894507850 | Host deadlines and backpressure; Android UDS deadline, three real pipe-fd exception paths and real VPN/SCM_RIGHTS ownership | passed |

## Separate fix commits

| Finding | Commit | Result |
|---|---|---|
| F1 | 101403a22d41c60c1c2fa4cf5f6698520bc958ab | Project captured protection policy into proxy socket settings |
| F2 | c1e32a4dd06ab14593b343588ad410108fc1544a | Set UDS timeouts before connect in subprocess and WebSocket clients |
| F3 | fb99686c269fd8efae4f1a21f358ae3d99bd7af4 | Keep protection and a retry owner until FullyReleased; preserve revoke reason |
| F4 | 2ce3d038b604f730c0d6dd050fa642d374919fd0 | Use the captured VPN lease for Naive profile checks and reject stale results |
| F5 | fedeea0db174363122ba76f14b1d69e95694b0e4 | Protect WARP/AWG, Hysteria2, TUIC, SS2022, tunnel DNS and Android DoQ before bind |
| F6 | afff010baee1ebf84d491e707ea2cba9a420df50 | Expire accepted and queued protect handshakes |
| Confirmed H3 | 14cbb52ab553561aa5122ec749a0a4cfd9ab8473 | Close the full received fd batch on every exception and close the control channel |

F3 uses FullyReleased as the only terminal release signal. A failed or timed-out stop can leave native work alive. Its original cause and protection owner remain available for the serialized retry.

## Observed local checks

- The 11 affected Rust packages passed with 1206 tests, zero failures and five existing ignored tests. They ran with default parallel test execution. Clippy passed with all targets and warnings denied.
- After rebase onto b2647d241, the affected tunnel-core and tunnel-intercept packages passed 548 tests with zero failures or skips. Their Clippy checks passed.
- Ten targeted service test classes passed 73 tests with zero failures, errors or skips. Service ktlint and detekt passed.
- Both Linux UDS backlog tests passed inside the existing offline, non-privileged Linux container.
- cargo deny --locked check passed. No package or dependency version changed; Cargo.lock adds eight edges to existing pinned packages.
- Locked Cargo metadata, native architecture contracts, task contracts and just ci-gates passed on the combined rebased tree. No baseline or golden fixture changed.
- Independent native, Kotlin service and async cancellation reviews passed. The final small test fixture and lint changes were reviewed again.

The Rust package set was ripdpi-native-protect, ripdpi-proxy-config, ripdpi-proxy-runtime-adapter, ripdpi-subprocess-protect, ripdpi-ws-tunnel, ripdpi-hysteria2, ripdpi-tuic, ripdpi-warp-core, ripdpi-tunnel-core, ripdpi-android-platform-adapter and ripdpi-relay-tls-transports.

Commands used cargo test --locked --jobs 4 --no-fail-fast, cargo clippy --locked --jobs 4 --all-targets -- -D warnings, and cargo deny --locked check. The stated package sets were passed with -p. Compiler-backed commands used build-gate --lane ripdpi, checkout-owned CARGO_TARGET_DIR, sccache and a four-job budget.

The Kotlin command used :core:service:testDebugUnitTest with filters for VpnServiceSessionCleanupTest, NaiveProxyProfileCheckerTest, PausedServiceReconstructionTest, VpnServiceSessionLifecycleTest, ActiveSessionOwnershipTest, VpnProtectSocketServerTest, LocalSocketClientSessionTest, ProtectSocketFailureBoundaryTest, ActiveProtectSocketPathProviderTest and ProtectSocketAcceptLoopTest, followed by :core:service:ktlintCheck and :core:service:detekt. It used -Pripdpi.skipNativeBuild=true for JVM tests only, --max-workers=4 and --console=plain.

## Android artifact and device checks

The exact source was clean before and after the build and device run. Gradle built :app:assembleGithubFullDebug and :app:assembleGithubFullDebugAndroidTest with -Pripdpi.localNativeAbis=arm64-v8a, -Pripdpi.nativeCpuBudget=4 and --max-workers=4. Native build was enabled. Build time was 16 min 56 s.

| Artifact | SHA-256 |
|---|---|
| app-github-full-arm64-v8a-debug.apk | c87f62a88ddaea7a546c0e8d8e05e0653840d85ac90d07a21a10034c396d59c7 |
| app-github-full-debug-androidTest.apk | 3dbd90079d2ab9f333e17421ccdb38c52294975b49a57c28b41db0ef8a415c73 |

The two bounded pool slots had missing or unsafe config.ini files. The documented temporary AVD fallback used API 37, google_apis, arm64-v8a and pixel_10_pro_xl. It held the pool lease through all device operations. The exact temporary device was emulator-5554; adb shell id reported UID 2000. The owned temporary AVD and its INI file were absent after cleanup.

Gradle :app:connectedGithubFullDebugAndroidTest used the same ABI and CPU flags and the class filter ProtectSocketDeadlineInstrumentedTest, ProtectSocketFdCleanupInstrumentedTest, ProtectSocketOwnershipInstrumentedTest. It passed all five tests in 52 s:

- silentClientsExpireAndTheNextDescriptorRequestIsHandled;
- extractionFailureClosesTheCurrentAndRemainingDescriptors;
- failureBeforeProtectionClosesEveryTransferredDescriptor;
- failureMonitorExceptionClosesEveryTransferredDescriptor;
- oldCleanupPreservesRealReplacementProtectEndpoint.

The first test checks real Android UDS recovery. The next three use real transferred pipe fds and kernel EOF as the closure oracle. The last test starts the real VPN service and checks real SCM_RIGHTS protection and stale-owner cleanup. These tests do not prove physical transport or external relay interoperability.

## Remote checks and integration

The seven fix commits were fast-forwarded into main and pushed to origin. Remote main was confirmed at 145387cae23bd1e8d39ac35eda3f9f016d0ebee8. GitHub accepted the explicit direct-main push and reported its configured bypass of the PR, pending ci-required and pending CodeQL requirements. No repository protection setting was changed.

[CI run for the source SHA](https://github.com/po4yka/RIPDPI/actions/runs/42504685684) is still running at this receipt. [CodeQL](https://github.com/po4yka/RIPDPI/actions/runs/42504685761), Secret Scan, harness-checks, Monitor-engine dependency guard, L7 adversarial dry-run and fleet-fixtures passed. The portfolio task stays in review until required remote evidence is complete.

## Limits and failure history

C is source evidence. V is an observed execution and is limited to the stated host, Linux or Android scenario. The changes do not raise the audit's S or E claims. No physical-device, external-server, root-activation or real-secret probe ran. The build used the existing Xray stub because its AAR was absent; it is not evidence for the optional Xray provider. Only the arm64 local native build is claimed.

The audit's H1, H2 and H4 remain hypotheses or scope limits. Peer authentication was not redesigned without a confirmed private-directory trust defect. Native pending-stop fault injection on an actual device and platform netd stalls remain separate acceptance checks.

Initial local attempts hit disk pressure, including a literal Rust ENOSPC and Gradle cache-write failure. A later full build passed after space recovered. Lint issues were fixed without suppressions or baseline changes. One test incorrectly required Throwable object identity across a coroutine boundary; it now checks type, message and the original object directly or as cause. A WebSocket fixture now keeps its accepted peer alive through the assertions. None of these failed setup or fixture runs was counted as product acceptance.
