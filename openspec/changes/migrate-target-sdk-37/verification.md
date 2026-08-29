---
task_id: AND-1787932839013427
change: migrate-target-sdk-37
commit_sha: null
local: required
local_evidence: Targeted tests, full unit/static gates, all app variants, all four native ABIs and bounded API 37 runtime probes passed; physical acceptance remains open.
remote_ci: required
remote_ci_evidence: No push or remote CI dispatch authorized.
device: required
device_evidence: API 37 16-KiB AVD grant/regrant passed; denied real-LAN enforcement is not provable through the emulator NAT. Physical API 37 remains unavailable.
artifact: required
artifact_evidence: Sixty Full/Simple debug/release variant APK outputs from the arm64 local build report min 27, compile 37 and target 37; baselineprofile reports min 28 and target 37. All-four-ABI native ELF evidence is tracked separately.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-T37-LAN | AND-1787933125693547 | Unit/service/diagnostics tests and bounded API-37 AVD grant/regrant; physical same-L2 denial pending | partial |
| REQ-T37-TLS | AND-1787933125835921 | Nested trust-failure tests and NSC generator tests passed; physical platform CT/ECH checks pending | partial |
| REQ-T37-RUNTIME | AND-1787933125533035 | All variants packaged at target 37; API-37 16-KiB JNI grant/regrant passed; physical lifecycle/UI/export pending | partial |
| REQ-T37-CI | AND-1787933125977204 | API 36/37 registry/workflow wiring and harness tests passed; hosted real-LAN topology and remote CI remain unverified | partial |

No requirement is fully accepted yet; two of five execution steps are complete. The local
evidence below is bounded by artifact, emulator-network and physical-device limits.

## Partial local evidence (2026-08-28, recovered worktree)

No requirement is accepted. Logs are retained in the workspace sibling
`target37-recovery-evidence/`; they are local evidence, not hosted CI.

- `python3 -m unittest scripts.tests.test_taskctl`: 26 passed; `./taskctl validate`: 47 tasks and 226 steps valid.
- `PermissionCoordinatorTest`: 7 passed; `MainViewModelTest`: 64 passed, including recovery of the rejected mode, stop cancellation of deferred recovery, and explicit-mode supersession (`inpath-green.log`, `stop-green-2.log`, `supersede-green-2.log`).
- `OwnedStackBrowserServiceTest`: 10 passed, including nested trust failures and typed transport fallback; `NscDomainEncryptionGeneratorTest`: 8 passed (`affected-tests-2.log`).
- `RipDpiManagedDevicesTest`: 3 passed after a failing test required explicit API-37 16-KiB selection (`managed-green.log`).
- `LocalNetworkAccessTest`: 3 passed. API-37 `AndroidLocalNetworkAccessTest`: 3 passed, including grant then revoke on the same checker and unresolved ordinary hostnames. The hostname regression first failed with `UnknownHostException`; the checker now leaves DNS failure to the actual operation rather than treating it as a permission denial (`hostname-red.xml`, `hostname-green.log`). The five diagnostics preflight tests also passed in that green run, with no skipped tests.
- `DiagnosticsLocalNetworkPreflightTest`: 5 passed after observed RED for mixed TCP admission, inner LAN through a loopback proxy, controller persistence, WEB legacy addresses, and a six-family admission matrix. Public results and explicit permission deferrals are retained in a partial report (`controller-green.log`, `web-green.log`, `families-green.log`).
- Python image-version, LAN fixture and instrumentation-evidence tests: 3 passed. The fixture unit test uses loopback and is not LAN acceptance. `actionlint` passed for the changed workflow.
- Architecture check: 23 current/23 baseline, no new, worsened or stale findings (`architecture-3.log`). No baseline was expanded. `cargo metadata --locked --no-deps` resolved 114 workspace members.
- Formatting/detekt regressions were fixed iteratively. App and diagnostics detekt, core data lint and `compileGithubFullDebugAndroidTestKotlin` passed (`format-lint-instrumentation.log`). Full `staticAnalysis` subsequently passed in 4m 26s: 734 tasks, including Full/Simple app lint and service lint (`static-analysis-9.log`). The three API-37 checker tests also passed again in that invocation. This validation used native-less compilation and is not packaging or runtime proof.
- An early diagnostics-only native run passed 118 tests but nextest flagged seven test processes
  as leaky (`rust-diagnostics-tests.log`). The later full workspace gate below supersedes that
  partial run and completed with zero `LEAK` results.
- CI now selects API-37 `android-37.0/google_apis_ps16k`, preserves actual image metadata, and requires TCP/UDP grant/deny/regrant smoke against a LAN fixture. The instrumented test compiled and ran on the API-37 AVD; the bounded results are recorded below.
- The separately produced real Xray AAR passed this worktree's release artifact verifier for all four ABIs (`xray-verify.log`). Its SHA-256 is `ca7b03fce7a6a447a40956435950aca4912427a6e2a2b02d545a8ac8609f8f1b`; producer paths were read-only. Its loader calls `System.loadLibrary`. This is not target-37 runtime acceptance.
- Twenty-two generated manifest entries declared target 37 during the early partial check (`observed-partial-manifests.json`). Packaged all-variant evidence superseding that partial check is recorded below.

## Packaging and API-37 runtime evidence (2026-08-29)

- Convention-plugin tests passed: 17 tests, zero failures or skips (`convention-full-2.log`). The full pluggable-transport CI snapshot from artifact 9694104222 was copied read-only, verified as 29 files across four ABIs with manifest SHA-256 `fd43a4dbee9bdb7759922e9df102c17e594e350223157fcefc67b13f9a2f1fa3`, and passed its owning Gradle verifier (`pluggable-prebuilt-verify.log`). This reuses producer evidence and is not API-37 acceptance.
- `:app:assembleGithubFullDebug` plus `:app:assembleGithubFullDebugAndroidTest` passed in 18m 53s with the real Xray AAR, full pluggable-transport snapshot and arm64 repository-owned native build (`assemble-api37-arm64.log`): 384 tasks, 118 executed, one from cache and 265 up to date. The all-packaged-library instrumentation update rebuilt in 43s (`assemble-api37-arm64-all-jni-test.log`).
- The preserved arm64 app APK SHA-256 is `d23173fca92a4509f7100a54239cfa59265e297579aaf9476e37efc2359c1ea5`; the final instrumentation APK SHA-256 is `5702da130396caafcc86d5499bc047d27cc8b168e5f9cec2c0ebb2701da0efdc`. `aapt2 dump badging` reports compile SDK 37, target SDK 37 and `native-code: arm64-v8a`; `zipalign -c -P 16 -v 4` reports `Verification successful` (`api37-apks-initial/`).
- The repository ELF verifier passed the five arm64 RIPDPI JNI libraries, including 16-KiB alignment and required exports (`api37-arm64-verify-native-elfs.log`). The installed APK contains 11 arm64 shared libraries. The grant run loaded all 11 through `System.loadLibrary`, including `libgojni.so` and all five RIPDPI JNI libraries, with no `UnsatisfiedLinkError` (`api37-runtime/smoke-default-image-compat-disabled/grant.txt`, corresponding logcat).
- Full `staticAnalysis` after the packaged-library test passed in 3m 51s: 732 tasks, including Full/Simple lint and androidTest analysis (`static-analysis-all-jni-test-2.log`). The immediately preceding invocation is recorded as failed because the command omitted the native wrapper and exposed an ambient `CARGO_BUILD_JOBS`; it did not report a code finding (`static-analysis-all-jni-test.log`).
- The API-37 16-KiB AVD ran image revision 6 with fingerprint `google/sdk_gphone16k_arm64/emu64a16k:17/CE2A.260420.019/15611780:user/dev-keys`, ABI arm64-v8a and `ro.debuggable=0` (`api37-runtime/device-summary.txt`). Grant and a later regrant each passed TCP, UDP, loopback, permission-preflight and all-library loading against the host's assigned `172.20.10.6` endpoint (`grant.txt`, `manual-regrant.txt`).
- Denied enforcement did not pass and is not waived. The image reports `RESTRICT_LOCAL_NETWORK` globally disabled; after an explicit per-package compat override and reboot it still routed `172.20.10.6` through the emulator alias gateway `10.0.2.2`, and denied TCP succeeded. Both strict failures are retained in `smoke-default-image-compat-disabled/` and `smoke-enforced/`; `observed-phases.json` contains only `grant`. The guest route is `172.20.10.6 via 10.0.2.2 dev eth0`, so the emulator NAT cannot supply a same-L2 real LAN endpoint. No default-denial acceptance is claimed.
- Production source has no direct `System.load(path)`, `MessageQueue` reflection or `static final` mutation. JNI loaders continue to use `System.loadLibrary`. The remaining production reflection is the existing file-descriptor extractor, which returns a typed inaccessible-field failure for `InaccessibleObjectException`, and a best-effort emulator detector.

## Final local implementation gates (2026-08-29)

- Demand-driven LAN admission now covers direct proxy/VPN endpoints, relay variants,
  incoming listeners, diagnostics targets, resolved IPv4/IPv6 names, loopback and the
  system-DNS exception. Permission revocation stops only a runtime recorded as LAN-dependent;
  tile and widget starts now resolve LAN admission before FGS dispatch and open foreground
  recovery without launching a background dialog. Tile admission runs in a main-thread action
  scope separate from listening, so closing the system panel no longer cancels the user-initiated
  recovery. Base policy, configured relay and VPN relay-race candidates share this preflight. The focused tile,
  widget, base-policy and relay-candidate tests passed after the expected compile-time and behavioral
  RED runs; app/service detekt and ktlint also passed. A final read-only review found no remaining
  P1/P2 issue in the tile/widget to pre-FGS admission path.
- Cancellation and checkpoint persistence retain explicit LAN deferrals in partial diagnostics
  reports. The focused diagnostics suite plus detekt and ktlint passed: 158 Gradle tasks
  (`diagnostics-partial-structure-green-4.log`).
- Active underlay fallback is deterministic when Android exposes only the public API surface:
  eligible Ethernet, Wi-Fi, cellular and other transports are ordered after rejecting VPN.
  Numeric IPv4/IPv6 resolution no longer incurs an unnecessary IO dispatcher hop; the
  API-37 address tests and the proxy policy-rebuild regression passed together
  (`local-network-literal-resolution-green-2.log`).
- The full project `testDebugUnitTest` passed after the relay-aware foreground preflight fix:
  333 actionable tasks in 41 seconds (`test-debug-unit-after-relay-foreground-preflight.log`).
  This supersedes the earlier 342-task successful run. The preceding failed run retained one
  reproducible proxy policy-rebuild race and is not counted as success.
- The final full `staticAnalysis` passed after the relay-aware foreground preflight fix:
  718 actionable tasks in 2 minutes 29 seconds
  (`static-analysis-after-relay-foreground-preflight.log`). Explicit locale lint
  for app Full and service resources previously passed: 530 tasks (`locale-lint-final.log`).
  Locale parity also reports all ten locales equal to their source keys (`locale-parity.log`).
  No lint, detekt, architecture or golden baseline was expanded.
- After the final tile/widget and relay-preflight changes, all 12 app assemble tasks passed on
  the current tree in 17 minutes 11 seconds: six distribution/experience combinations in debug
  and release, 1,320 actionable tasks (`assemble-all-app-variants-final-tree.log`). This local
  packaging run used `ripdpi.nativeAbisOverride=arm64-v8a`, the verified real Xray AAR and the
  pinned full pluggable-transport snapshot. Its 60 generated variant outputs (four ABI split
  names plus universal output per variant) all report min SDK 27, compile/platform SDK 37,
  target SDK 37 and `ACCESS_LOCAL_NETWORK`
  (`all-app-apk-sdk-summary-final-tree.txt`). The non-arm64 split names from this host-ABI run
  are variant/manifest evidence, not a claim that repository-owned JNI libraries were packaged
  for those ABIs. The separate all-four-ABI native evidence is recorded below.
- `:baselineprofile:assembleDebug` passed again on the final tree; `aapt2` reports min SDK 28,
  compile/platform SDK 37 and target SDK 37
  (`baselineprofile-assemble-debug-final-tree.log`).
- Repository-owned JNI artifacts were rebuilt for armeabi-v7a, arm64-v8a, x86 and x86_64 in
  31 minutes 30 seconds, and the owning ELF/export/16-KiB verifier passed
  (`native-all4-android-jni.log`, `native-all4-elf-verification.log`). Xray and pluggable
  transport inputs remain read-only producer artifacts; their checks do not prove target-37
  runtime behavior.
- `actionlint`, task contracts (47 tasks, 226 steps), architecture health (23 current/23
  baseline, zero new/worsened/stale), Cargo metadata for 114 locked workspace members, and
  30 Python task/image/LAN-harness tests passed. No hosted CI was dispatched.
- The canonical native lint gate passed rustfmt, runtime/unsafe/FFI/drop-order/soundness
  guards, `clippy --locked --workspace --all-targets -- -D warnings`, and rustdoc
  (`rust-lint-target37-final.log`). The canonical native workspace gate then passed hotspot
  budgets, architecture checks, 5,232/5,232 filtered workspace tests with zero `LEAK`
  results, and the ignored Android startup smoke 1/1 (`rust-workspace-tests-target37-final.log`).
  Three workspace tests exceeded nextest's 30-second slow threshold but completed successfully;
  32 tests remain intentionally excluded by the repository's platform/network filter. The
  separate locked workspace doc-test run also passed: two executable examples passed and three
  examples remained explicitly ignored (`rust-doc-tests-target37-final.log`); Android cdylib
  packages correctly report that their crate type does not support doc-tests.
- After rebasing the change onto remote `main` at `ec7f670cd`, the combined-tree architecture
  gate initially found one new oversized Kotlin source caused by the merged VPN coordinator.
  Permission-revocation handling was extracted into a focused component; the gate then returned
  to 23 current/23 baseline indicators with zero new, worsened or stale findings. The focused
  service unit/detekt/ktlint gate passed 226 tasks in 2 minutes 17 seconds
  (`rebased-service-gate.log`).
- The rebased combined Gradle gate passed convention-plugin tests, all debug unit tests and full
  `staticAnalysis`: 840 actionable tasks in 4 minutes 54 seconds
  (`rebased-combined-gradle-gate.log`). The complete 12-task app packaging matrix then passed on
  the same tree in 31 minutes 12 seconds with verified Xray/PT inputs: 1,324 actionable tasks
  (`rebased-assemble-all-app-variants.log`). A fresh `aapt2` pass again found 60/60 app outputs
  at min SDK 27, compile/platform SDK 37 and target SDK 37 with `ACCESS_LOCAL_NETWORK`
  (`rebased-all-app-apk-sdk-summary.txt`). This remains local combined-tree evidence, not hosted
  CI or physical Android-17 acceptance.

### Validation environment recovery (2026-08-29)

The first hostname retries did not reach a behavioral test: the Gradle transform cache
referenced a removed convention JAR, followed by Plugin Portal resolution/HEAD failures.
A fresh daemon and a local init script (`plugin-repositories.gradle`) prioritize the
same official Maven Central and Gradle Plugin Portal repositories without changing
project dependencies. Only `hostname-red.xml` is the observed behavioral RED.

`static-analysis-6.log` reached a service detekt failure and then stalled in lint's
Google Maven metadata requests. The task's own client was interrupted; this run is
failed/cancelled, not a completed gate. Direct retrieval of the official
`https://dl.google.com/dl/android/maven2/master-index.xml` returned HTTP 200, while
`maven.google.com` was unreachable. Subsequent local commands set the lint-supported
`GMAVEN_TEST_BASE_URL` to that official production repository URL; no fixture metadata
or disabled lint checks are used. `static-analysis-7.log` completed with six model
detekt findings after service detekt and service lint passed. Address classification
was refactored without changing its policy; all three address-policy tests passed
again. The `--continue` run (`static-analysis-8.log`) found the remaining settings
port-constant, shadow visibility and shadow filename findings. After those fixes,
`static-analysis-9.log` passed the full gate. No baselines or golden fixtures changed.

### Disk recovery and device coordination

The earlier worktree and its uncommitted changes were removed during disk cleanup.
Source changes were recovered from the local task log into a dedicated worktree; the
main checkout's unrelated lifecycle edits remain intact. Prior ENOSPC and inaccessible
`jdk.internal.access.SharedSecrets` failures remain historical failures, not successful
checks. The recovered API-37 tests now execute with the test-only Java 21 launcher.

The API-35 AVD at `emulator-5570` belongs to the Xray acceptance task. This migration
stopped its own hung read-only `getprop` command and does not restart or install onto
that AVD. This task created and booted its own `codex_ripdpi_target37_20260828` at
`emulator-5580`: SDK 37, page size 16384, image revision 6, fingerprint
`google/sdk_gphone16k_arm64/emu64a16k:17/CE2A.260420.019/15611780:user/dev-keys`.
The first software-GPU boot ended before readiness; the second GPU-auto boot and
fresh device property checks passed. No app or LAN acceptance is implied by boot.

## Remaining implementation and acceptance

- Run grant, denial, repeated denial, revoke and regrant against a controlled same-L2 LAN peer
  on a non-root physical Android 17 device. The AVD proves grant/regrant, loopback, APK/JNI
  loading and typed preflight, but its NAT route cannot prove kernel denial. Per-socket native
  enforcement and dynamic hostname changes therefore remain physical acceptance gaps.
- Run the hosted API 27/33/35/36/37 matrix and retain its actual image metadata. The standard
  hosted emulator NAT cannot satisfy the spec's non-loopback direct-route LAN requirement;
  missing topology must fail or leave acceptance incomplete rather than report a false smoke.
- Complete physical lifecycle/UI acceptance for VPN consent and revoke, boot, tile, widget,
  always-on/lockdown, network handover, process death, predictive back, IME, large screen,
  RTL/large font and cross-application SAF/FileProvider import/export. Existing unit and
  packaging evidence does not substitute for those runtime observations.
- Exercise platform HTTP and OkHttp trust/CT behavior on API 37 with controlled valid and
  invalid certificates. The NSC serializer and nested fallback guards are tested, but a
  successful HTTPS request is not proof of CT or ECH negotiation and native rustls/Go paths
  retain separate trust boundaries.
- The clean full native test/clippy gate and all-four-ABI ELF checks passed. Dedicated
  network-e2e, turmoil and CAP_NET_ADMIN suites remain outside the canonical workspace filter
  and are not claimed by this target-37 run.
- No commit, integration, push, hosted CI, publication or closure is authorized or claimed.
  Existing ECH and split-tunnel tasks remain open.
