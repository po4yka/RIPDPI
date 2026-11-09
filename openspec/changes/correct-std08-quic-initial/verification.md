---
task_id: DGN-1794211297803022
change: correct-std08-quic-initial
commit_sha: 847a0dfc5aa439143eb45c49ea2cd1e4334a2388
local: required
local_evidence: "Rebased source 847a0dfc5 passed 560 native tests, full staticAnalysis, 26 selected JVM tests, architecture health and locked metadata. Its workspace Clippy failed with query-depth overflow in the published XHTTP connect_reality future; the separate XHTTP check reproduced it. XHTTP source and Cargo configuration match origin/main. Earlier source 972f610c9 passed workspace Clippy and ci-gates. Final source-gate and scoped Clippy receipts are recorded below. No limit, suppression or dependency was changed."
remote_ci: required
remote_ci_evidence: Pending the authorized push.
device: required
device_evidence: Source 2cbf12155a (runtime source 972f610c9 plus a test-only responder repair) passed all four minified ARM64 API 37 methods with real JNI, zero failures or skips. Each method ran exactly once. The actual ARM64 ELF has 41 exports with no inventory difference. No physical device or external server acceptance is claimed.
artifact: not_applicable
artifact_evidence: No release or published binary is owned by this repair.
deployment: not_applicable
deployment_evidence: No deployment is owned by this repair.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-QUIC-ACTIVE-HELLO | DGN-1794211430951611 | Final native suite and independent rustls HP/AEAD/TLS oracle: three profiles, both versions, DNS/IPv4/IPv6 (18 cases); IP SNI absence asserted separately; TLS 1.3 only | Passed locally |
| REQ-QUIC-RESPONSE-INTEGRITY | DGN-1794211431545271 | Official RFC Initial/Retry, malformed/VN, large-response native tests; all 11 JNI facade tests; exact approved 41-export ELF inventory; selected JVM tests; four actual minified Android methods | Passed locally |
| REQ-QUIC-INITIAL-TOKEN | DGN-1794211432222833 | Both token regressions and independent v1/v2 wire equality in final packet suite | Passed locally |
| REQ-QUIC-INITIAL-ACK | DGN-1794211432765485 | ACK/ACK_ECN and malformed-range regressions, independent v1/v2 vectors | Passed locally |
| REQ-QUIC-DATAGRAM-CRYPTO | DGN-1794211433260336 | Final packets/desync/TUN suites; four TShark captures for v1/v2 coalescing and short final packets; physical marker offsets | Passed locally |
| REQ-QUIC-HELLO-COMPLETE | DGN-1794211433822597 | Final packet completeness regressions, nested-vector rejection and real consumer boundaries; independent review clear | Passed locally |
| REQ-QUIC-PROBE-CANCEL | DGN-1794211434635760 | Final six transport tests and 13 probe tests: real loopback cancellation, port rebind, shared deadline, bounded DNS admission and full datagram reception | Passed locally |

## Scope

Audit baseline: a7e8417a4baa2b96919f003f218884ab0da6d9d4. Native combined checks use integration base 7bd65bae644a250a6d00c4207426be1fdffa8607 and candidate HEAD 7f356e8c86c5dcac2cb68a2484add155d9fda14d plus the staged F2 patch (SHA-256 9234d7cb372ac3f3dd71f4845020c92f4e79d6587894285af7c5decb7612d5c6). The preceding audit observed the failures on the same STD-08 source tree. C, local V, selected RFC S and external E remain separate. No external E is claimed. Do not archive until required evidence is resolved.

## JNI export approval

The new validation export needs one inventory addition. JNI_CONTRACT.md:585-588 requires an explicitly approved regeneration of jni-symbols.baseline. Source and tests passed independent review. NDK 29 llvm-nm extracted the actual ARM64 release library exports: 40 to 41, with only Java_com_poyka_ripdpi_diagnostics_dpi_NativeQuicInitialPacketBindings_jniValidate added and no removals. The user explicitly approved this exact inventory regeneration on 2026-11-09. The inventory was regenerated from the actual library with the owning NDK llvm-nm procedure. `.github/scripts/check-jni-symbols.sh` passed against that library.

Minified local acceptance uses the release build type with a temporary init script that selects the disposable debug signing key. No production signing material, physical device or external probe is used.

## Historical combined local checks

Cargo commands ran from `native/rust` with the pinned toolchain and `--locked`.
Heavy commands used `build-gate --lane ripdpi` and task-owned output directories.

- `cargo test -p ripdpi-packets -p ripdpi-desync --locked`: desync 151 unit and seven integration tests passed. The first combined run found one test assertion made stale by the upstream strict SNI fix. Commit 7f356e8c8 limits that assertion to intact nested SNI. The QUIC rejection checks remain for all nine mutations and both versions.
- `cargo test -p ripdpi-packets --locked`: the corrected full packet suite passed, 284 unit and five golden-contract integration tests, no failures or ignored tests. No golden bytes changed.
- `cargo test -p ripdpi-android-platform-adapter --locked quic_initial`: five tests passed; `quic_response`: two tests passed.
- Strict rustls QUIC consumer integration: one test passed for three profiles and both versions. This checks handshake input, not a complete connection to an external server.
- `cargo clippy --locked --workspace --all-targets -- -D warnings`: passed. Packet all-targets Clippy passed again after the test assertion change.
- `:core:diagnostics:testDebugUnitTest` for `DatagramSocketQuicUdpProbeTest`, `QuicH3FingerprintProbeTest`, and `QuicFingerprintFactoryTest`: 25 tests passed, none skipped. These include real loopback cancellation, port rebind, and bounded DNS admission.
- `cargo test -p ripdpi-android --locked --lib`: all 11 facade tests passed, no failures, ignored or filtered tests. This includes JNI signature and approved export inventory membership. The actual ELF symbol checker also passed.
- `just ci-gates`: passed on the combined rebased candidate. The Linux-owned runtime-platform API snapshot check is not run by the macOS gate.
- `./gradlew staticAnalysis`: failed on four existing issues: TooGenericExceptionCaught in `NativeEchTlsHandshake.kt:74`, ReturnCount in `HttpsRrResolver.kt:77` and `HttpsDnsWireParser.kt:53`, and MaxLineLength in `HttpsRrAliasResolverTest.kt:113`. All four files have no diff from either the audit start or published integration base. No suppression or baseline change was made.

Logs: `/tmp/std08-combined-native-packets-final.log`, `/tmp/std08-combined-native-workspace-clippy.log`, `/tmp/std08-combined-native-packets-final-clippy.log`, `/tmp/std08-combined-static-analysis.log`, `/tmp/std08-rebased-ci-gates.log`, `/tmp/std08-f2-kotlin-final.log`, and `/tmp/std08-jni-facade-final.log`.

## Build prerequisites and retries

The audit checkout lacked two vendored boring-sys build scripts. The user approved their exact restoration as a separate prerequisite commit. The adapter check passed after restoration. Published main later contained the same restoration in a43980af2, so rebase dropped the duplicate prerequisite commit.

The first release build exhausted disk space in task-owned build outputs. Its errors are not source failures or successful acceptance. After removal of completed task-owned temporary targets, a retry hit an execution-history lock in the old Gradle process. A fresh `--no-daemon` process is used for the next build. Local Android acceptance uses the supported `ripdpi.nativeAbisOverride=arm64-v8a`; it does not establish four-ABI build coverage. The real cached Xray AAR passed the owning release verifier, including its pinned source and patch metadata and four ABI ELF checks (SHA-256 ba252f28e6fe8aea726254d6aa6b550c7891027e9e56de2bee3870546f96e9cd).

## Android candidate provenance

The first completed ARM64 `libripdpi.so` is 11,562,520 bytes, SHA-256 ea7c29b3f7e0402d04f3776d50fabfcd25c75f0aa746132313dbd795739a8c2d. It was built from HEAD 7f356e8c86c5dcac2cb68a2484add155d9fda14d plus the staged F2 patch identified above. Actual exports and metadata are in `/tmp/ripdpi-std08-android-acceptance/libripdpi-arm64-jni-symbols.actual.txt` and `libripdpi-arm64-inventory.json`. The source inventory was then regenerated with explicit user approval; its previous SHA-256 was 772ca6ee60d3a1fc9df3c48007cbef802f663db8aa4aeb266d402f822f5c7c96. This inventory-only addition does not change the library runtime source. APK, R8 and instrumented results at this stage were still pending; the final result is below.

The subsequent release packaging attempt stopped on the absent pinned upstream NaiveProxy binary. The owning command `python3 scripts/ci/fetch-naive-upstream.py --abi arm64-v8a` then passed. The downloaded ignored `libnaive.so` SHA-256 is 23f950b4a813a63456070a7780001658da21e059b352f5a441ae8e66e5c97a74, equal to the pin. No stub or tracked binary change was made.

## First minified Android attempt

The release and AndroidTest APK assembly passed with R8. The main release APK SHA-256 is a0b648eabf6353a92fc8920118c09ba36c97d80f85f487298eb861f38bf011d0. Its filename contains `universal`, but the supported local ABI override packages only ARM64. Its packaged `libripdpi.so` matches the ELF SHA above. R8 retains the binding class and both native method names, including `jniValidate(byte[], byte[], boolean)`.

Both idle pool entries lacked their AVD `config.ini`. The documented temporary lease was used with this reason and automatic cleanup. The first device attempt returned Gradle exit 0 but executed zero requested tests: JUnit rejected the expression-body loopback test because Kotlin inferred `java.lang.Void` instead of JVM `void`. The owning result validator rejected this run. This is a failed attempt, not JNI execution evidence. The regression test now uses `runBlocking<Unit>`; no production source changed. The new staged candidate patch, including the approved inventory and this test-only fix, has SHA-256 3bd2a1d7f4d7e6d03a5673986b27e741f531da60bb946c4c1c41a06b9651889e. The repeated device run requires each of the three methods exactly once and forbids skips. Failed-attempt artifacts remain under `/tmp/ripdpi-std08-android-acceptance/attempt1-initialization-failure/`.

## Tested-app R8 boundary

After the JVM return-type correction, all three methods ran: two passed, one failed, none skipped. The direct JNI methods passed official v1/v2 Initial/Retry and malformed/VN checks. The loopback method failed with `NoSuchMethodError` for the synthetic default probe constructor. Actual R8 mapping shows that production optimization shortened this constructor to `(I)V`, narrowed `check` to `ContinuationImpl`, and removed the six result getters. This is not a completed loopback check.

The repair adds `app/proguard-androidtest-target-rules.pro` only when the existing `testBuildType` is `release`. It preserves the exact constructor, check method, six result getters and one verdict field used across the AndroidTest APK boundary. Member names can still be obfuscated. Normal release shrinking is unchanged; no full-class keep or production test seam was added. An independent explorer confirmed the owning configuration and all affected descriptors. The staged candidate patch SHA-256 is fae3cb6ffeded0a6af5a8a01d36efc11c654427599eb81268a4971ad16113a62. Attempt-two evidence is under `/tmp/ripdpi-std08-android-acceptance/attempt2-r8-constructor-failure/`. The final run below uses these narrow target rules.

## First frozen-candidate Android result (before IP-target correction)

`GithubFullRelease`, ARM64, API 37, `google_apis`, `pixel_10_pro_xl`: all three requested tests passed, zero failures and skips. The owning JUnit validator required each named method exactly once and total count three. The temporary AVD was removed by its lease wrapper. No active build or device lease remains.

The source snapshot was HEAD 7f356e8c86c5dcac2cb68a2484add155d9fda14d plus staged patch fae3cb6ffeded0a6af5a8a01d36efc11c654427599eb81268a4971ad16113a62, now committed as 857a1bf858b781db70d1b166f1753d2165b64d83. The final app APK SHA-256 is 18ab145345d9fbe3cc187c19e6abf93345157865a257f81a40285a5018124bb7, test APK e84e440c4612f2dff3fc4aad2958f2ecd7c13c6af9bdcc2a25336f052677ffc5. Packaged native library SHA remains ea7c29b3f7e0402d04f3776d50fabfcd25c75f0aa746132313dbd795739a8c2d, with the approved 41-export inventory. This is local ARM64 acceptance with a disposable debug key and narrow instrumentation target member rules, not four-ABI or external interoperability acceptance.

Exact outer command:

```sh
python3 scripts/ci/android-avd-pool.py run --api 37 --arch arm64-v8a --target google_apis --profile pixel_10_pro_xl --temporary --reason 'Pool slots are missing config.ini; validate minified QUIC JNI with narrow instrumentation target rules' -- bash /tmp/ripdpi-std08-device-acceptance.sh
```

The inner command and all exact Gradle properties are preserved in `/tmp/ripdpi-std08-android-acceptance/exact-device-command.sh`. It runs `:app:connectedGithubFullReleaseAndroidTest` through `build-gate --lane ripdpi`, with the real pinned Xray AAR, native ABI override, temporary signing init script, exact test class and fresh single-use Gradle process. All ADB calls use the leased serial.

Preserved local evidence copies under `/tmp/ripdpi-std08-android-acceptance/final-frozen-artifacts/` include the two APKs, final JUnit XML and HTML report. `acceptance-summary.json`, `release-artifacts.json`, `r8-jni-evidence.txt`, `r8-test-evidence.txt`, and `junit-validation.log` record provenance, exact method counts, preserved native names and the corrected JVM void test signature. The two earlier failed attempts remain separate and are not counted as passes.

## Earlier integration candidate and F1 IP-target variant

Final integration base: a21f568c218f4489c37c449468623226703ac810. Rebase preserved the published HTTP, TLS and transport changes. On candidate 03721f722, full packets 308+5, desync 159+7, adapter 5+2, strict rustls and facade 11 passed. Workspace Clippy then found two new `inspect_http` arguments missing from this repair's TUN regression. Commit 710f36824 adds `false` to those two test calls; every assertion remains. Full TUN tests then passed 60/0 failures, and exact workspace all-targets Clippy passed. `just ci-gates`, architecture health and locked Cargo metadata passed. The four files with existing staticAnalysis issues still match both the audit baseline and current origin/main.

### F1b — P2: literal IPv4 target was sent in SNI

This is a reachable variant of F1. `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/state/DiagnosticsDpiSuiteExecutionSupport.kt:145-152` passes custom targets through `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/dpi/DpiTargetHosts.kt:4,21`, which accepts canonical IPv4 targets. The runner passes them to the native packet factory. `native/rust/crates/ripdpi-android-platform-adapter/src/quic_initial.rs:38-44` uses the target as the host, and the active builder previously serialized it as `host_name`. [RFC 6066 section 3](https://www.rfc-editor.org/rfc/rfc6066.html#section-3) excludes literal IPv4 and IPv6 addresses from HostName. Thus an allowed UI input produced a nonconforming ClientHello. The effect is invalid wire content and changed server-name handling; rejection by a concrete external peer was not run.

The owning unit test failed before correction with `GoogleChrome sends IP 127.0.0.1 as SNI`. An independent rustls HP/AEAD decoder exposed the same extension in the generated wire and its presence assertion failed. Rustls TLS acceptance alone passed this input while exposing no server name, so that permissive result is not used as proof of SNI compliance.

Commit 49edcd11a skips the SNI extension for targets parsed as `std::net::IpAddr`. It does not pass `None`, which would retain the profile's template SNI. DNS input, absent-host defaults and fake/decoy builders retain their behavior. The unit regression and independent wire/TLS regression then passed. The independent oracle checks all 18 DNS/IPv4/IPv6, profile and v1/v2 combinations with rustls Initial keys, header protection, AEAD and TLS processing. It uses no production QUIC decoder or copied cryptographic routine; a bounded test reader only checks generated framing and TLS extension presence. No dependency was added. Independent review found no issue.

| Variant | Expected behavior | Implementation and reachability | Evidence | Result |
|---|---|---|---|---|
| v1 active IP target, all three profiles | Protected Initial with no SNI; valid ALPN and source-CID parameters | Active builder and native adapter; IPv4 custom-target production path | Owning unit; nine independent wire/TLS cases for v1; RFC 6066 section 3 | Implemented, local C/V/S including minified IPv4 loopback; no external E |
| v2 active IP target, all three profiles | Same behavior with v2 Initial protection | Public packet backend and test consumer; this does not claim a v2 Kotlin fingerprint profile | Owning unit; nine independent wire/TLS cases for v2; RFC 6066 section 3 | Implemented, local C/V/S; no external E |
| IPv6 input | Omit SNI in the backend | Plain IPv6 is supported by the native builder; the current custom-domain UI rejects colon syntax | Independent backend wire/TLS cases | Backend implemented; no IPv6 UI or network acceptance claimed |

Red logs: `/tmp/std08-ip-sni-red-unit.log` and `/tmp/std08-ip-sni-red-independent-wire.log`. Green logs: `/tmp/std08-ip-sni-green-unit.log` and `/tmp/std08-ip-sni-green-independent-wire.log`. The separate earlier Rustls-only acceptance is not counted as proof that the forbidden SNI extension was absent.

Source 49edcd11a848fb2050ef1df0a0985e5e0392dd71 passed the full repeated native suite, workspace Clippy, ci-gates and the fresh minified ARM64 three-method run. The preceding rebased assembly passed but was deliberately not installed for acceptance after the IP defect was confirmed; its artifacts remain in `/tmp/ripdpi-std08-android-acceptance-rebased/pre-ip-sni-fix-artifacts/`.

## IP-corrected acceptance result

On 49edcd11a848fb2050ef1df0a0985e5e0392dd71, all 559 requested native tests passed, none failed or ignored. `cargo clippy --locked --workspace --all-targets -- -D warnings` passed. `just ci-gates` passed, with the documented Linux-owned runtime-platform snapshot excluded on macOS. Logs: `/tmp/std08-ip-final-*.log`, `/tmp/std08-ip-final-ci-gates.log`, and `/tmp/std08-ip-final-symbol-check.log`.

The actual minified release and AndroidTest assembly passed in 18m17s. The three requested instrumented methods then passed in 56s, with zero failures or skips; each method passed the owning exact-count validator. The temporary API 37 ARM64 AVD was removed, and no active lease remains. The official RFC v1/v2 positive and negative JNI checks and real malformed IPv4 loopback check all ran. Local signing used the disposable debug key and the exact test target member rules described above.

App APK SHA-256: 8bbf62d179eac13e2ac6295cfc057faeef0e18bcb3f06c47bf9bc46521298c50. Test APK: bc04925fc9700d1c8a4ae9762164cca3d5903f0701714381ed2141cf37cd8e1b. Packaged ARM64 `libripdpi.so`: b09833a2bb65ac0872c922f925df43eff59cd37537b392cd51e758350b3d857f, 11,563,896 bytes, 41 JNI exports with no inventory difference. `.github/scripts/check-jni-symbols.sh` passed on this actual library. R8 retains the binding class and both native method names.

Evidence is under `/tmp/ripdpi-std08-android-acceptance-ip-final/`: `acceptance-summary.json`, `release-artifacts.json`, `exact-device-command.sh`, `r8-jni-evidence.txt`, `junit-validation.log` and `final-frozen-artifacts/` containing APK copies, JUnit XML, HTML and compressed mapping. This is local ARM64 V, not physical-device, four-ABI, external-server or complete QUIC/HTTP3 session E.

Published base 3bc146510 includes separate repairs for the four original staticAnalysis violations. It does not change the QUIC generator, JNI bindings, Kotlin probe or R8 target rules. QUIC crypto/JNI dependency versions, sources and checksums (AES/AES-GCM, HKDF, SHA2, ring, rustls, jni and jni-sys) also match the earlier tested integration base. Later checks below use the final source; the 49edcd11a binary evidence remains historical.

## Final source and complete UDP responses

The final runtime source is `972f610c9cd8e4cd2506ab7cde54acfbeccb708a`, based on published main `d1cb9f52687d13bc47ae551a2a983d991a487264`. Rebase preserved other work. The nine fix commits are F3 `1c6ab2a10b`, F4 `9508cac8e3`, F1 `f5c7bf2e7a`, F7 `5027a2e5e7`, F6 `7efcee3486`, F5 `cdcf4f7325`, F2 `847a592312`, F1b `8e5cbeafe8`, and F2b `972f610c9c`. Earlier IDs above identify historical candidates.

### F2b — P2: a single valid response was truncated to 2048 bytes

`DatagramSocketQuicUdpProbe` used a 2048-byte receive packet. The active ClientHello does not advertise a smaller `max_udp_payload_size`; RFC 9000 section 18.2 gives 65527 as its default. A 3000-byte server Initial fits the controlled loopback path and the three-times-1200 anti-amplification budget. It is one datagram and needs no multi-datagram reassembly. Android's `DatagramSocket.receive` contract truncates a response that exceeds the supplied packet length. This limit could remove the AEAD tag and produce a false dropped verdict.

The real JVM regression first failed with expected length 3000 and actual length 2048 (`/tmp/std08-datagram-red.log`). The fix changes only the bounded receive capacity to 65535. The regression now checks byte-exact responses of 3000 and 8192 bytes over real loopback UDP; all six transport tests passed. Cancellation, the shared deadline, socket closing and bounded DNS admission remain in effect.

Two independent 3000-byte server Initial fixtures cover v1 and v2. They use the official RFC plaintext plus PADDING, updated Length and pinned rustls 0.23.46 protection APIs. They are derived packets, not verbatim RFC vectors. Native validation accepts each full packet and rejects its 2048-byte prefix; it also checks damage and all truncations. TShark 4.6.9 independently decoded ServerHello in both captures without a malformed/decryption error. Fixture and temporary producer hashes are in `native/rust/crates/ripdpi-packets/tests/fixtures/quic/README.md`. The producer source is outside the checkout. Independent review found no issue.

The fourth Android method, `nativeBindingValidatesCompleteLargeUdpResponses`, links real UDP exchange to native integrity validation for both versions. Its cross-APK calls use exact test-only R8 member rules.

The first final-source device run executed all four methods: three passed, one failed, zero skipped. The large-response method failed before UDP receive with `NoSuchMethodError` for `Dispatchers.getIO()`. Actual app R8 mapping shows that this library accessor was removed. The separate test APK could still call it. This is a failed acceptance attempt, not evidence for whole-datagram reception. Artifacts remain in `/tmp/ripdpi-std08-android-acceptance-datagram-final/attempt1-coroutine-r8-failure/`; its temporary AVD was removed. Commit `2cbf12155a` replaces only the test responder's coroutine with a thread, an error reference and bounded join, as in the existing adjacent test. All packet assertions remain. It adds no library keep rule or production change. The repeated run requires all four methods again.

### Final local gates

- Native suites: packets 309 unit and five integration; desync 159 and seven; TUN 61; adapter Initial five and response two; independent rustls oracle one with 18 combinations; facade 11. Total 560 passed, zero failed or ignored.
- `cargo clippy --locked --workspace --all-targets -- -D warnings`: passed.
- Full `staticAnalysis` and the three selected JVM classes: passed; 26 tests, zero failures, errors or skips. Upstream changes corrected the four historical failures. No suppression was added.
- `just ci-gates`: passed. The Linux-owned runtime-platform snapshot remains excluded on macOS.
- `python3 scripts/ci/check_architecture_health.py` and locked Cargo metadata: passed.
- Actual ARM64 ELF: SHA-256 `9a5d23d0562e42c7adcead05c0740df7bb469f43b4366ba517ed50ab14cf0f72`, 11585552 bytes, 41 exports. `.github/scripts/check-jni-symbols.sh` passed with the approved inventory.

Logs are `/tmp/std08-datagram-final-{packets-desync-tun,adapter-quic-initial,adapter-quic-response,strict-rustls,android-lib,workspace-clippy,static-jvm,ci-gates,architecture-health,symbol-check}.log` and `/tmp/std08-datagram-final-cargo-metadata.json`. These final-source results supersede earlier gate totals without erasing failed attempts.

Native commands ran from `native/rust` through `build-gate --lane ripdpi -- env RUSTC_WRAPPER=sccache CARGO_TARGET_DIR=/tmp/ripdpi-std08-native-target bash -c '…'`, in sequence:

```sh
"$HOME/.cargo/bin/cargo" test -p ripdpi-packets -p ripdpi-desync -p ripdpi-tunnel-intercept --locked
"$HOME/.cargo/bin/cargo" test -p ripdpi-android-platform-adapter --locked quic_initial
"$HOME/.cargo/bin/cargo" test -p ripdpi-android-platform-adapter --locked quic_response
"$HOME/.cargo/bin/cargo" test -p ripdpi-diagnostics-runner --locked --test quic_probe_client_hello
"$HOME/.cargo/bin/cargo" test -p ripdpi-android --lib --locked
bash ../../scripts/ci/cargo-guarded.sh cargo clippy --locked --manifest-path Cargo.toml --workspace --all-targets -- -D warnings
```

Exact static/JVM command from the worktree root:

```sh
build-gate --lane ripdpi -- env -u RIPDPI_SIGNING_STORE_FILE -u RIPDPI_SIGNING_STORE_PASSWORD -u RIPDPI_SIGNING_KEY_ALIAS -u RIPDPI_SIGNING_KEY_PASSWORD GRADLE_USER_HOME=/tmp/ripdpi-std08-gradle-home JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-21.jdk/Contents/Home ./gradlew staticAnalysis :core:diagnostics:testDebugUnitTest --tests '*DatagramSocketQuicUdpProbeTest' --tests '*QuicH3FingerprintProbeTest' --tests '*QuicFingerprintFactoryTest' -Pripdpi.skipNativeBuild=true -Pripdpi.prebuiltXrayAarDir=/tmp/vpn07-ci35-repro-xray-native --no-daemon --max-workers=4
```

### Remaining boundaries

External interoperability E, physical devices, four-ABI runtime acceptance, long fuzz/load runs and the Linux-only API snapshot were not checked here. Stateful reassembly across datagrams, late packet-number reconstruction, full TLS authentication/H3, recovery and congestion control remain outside STD-08 packet-helper scope. Initial integrity is not peer authentication. Required remote CI remains pending; do not archive yet.

## Final four-method Android receipt

Source `2cbf12155a532327c073d0e58e6a5d5bd4cf7698` passed the actual minified `GithubFullRelease` API 37 ARM64 run: four passed, zero failed or skipped. Each of the four owning JUnit validators required its method exactly once and total count four. The native source is 972f610c9; the only later source change is the test responder repair. The large-response test received each full 3000-byte v1/v2 packet through real loopback UDP and JNI; each 2048-byte prefix remained a negative control. The temporary AVD was removed and no build or device lease remains.

App APK SHA-256: `2b482349ad3a0a9f2fb0028ebd0b0fb7bda2cc494b81b3c90faa30cff962ac52`. Test APK: `2899bf1f96997652ccff24dc371f822fddb2ae4aeaeb63209850249698003cc1`. The packaged native ELF matches `9a5d23d0562e42c7adcead05c0740df7bb469f43b4366ba517ed50ab14cf0f72`, with 41 approved exports. R8 JNI names and exact transport member boundaries were checked against the actual mapping. Local signing uses the disposable debug key; this is not a published release.

The connected Gradle task passed in 8m11s. Exact inner and outer commands, the temporary signing init script, acceptance summary, R8 evidence and failed-attempt artifacts are under `/tmp/ripdpi-std08-android-acceptance-datagram-final/`. Its `final-frozen-artifacts/` retains both APKs, JUnit XML, HTML report and compressed app mapping. The earlier three-pass/one-fail attempt is separate and is not counted as a pass. External E and four-ABI runtime acceptance remain unclaimed.

## Publication candidate after final rebase

Source `847a0dfc5aa439143eb45c49ea2cd1e4334a2388` is based on published `a91bc4d30c59a885f4411e7f78e98c3c09482e05`. The accepted QUIC sources, manifests/lock/toolchain, JNI sources, Kotlin probe, instrumented test and R8 rules have no diff from the accepted 2cbf12155a candidate. `/tmp/std08-publish-final-accepted-source.diff` is empty. Upstream differences concern DNS, XHTTP and their fixtures; the original acceptance source and APK hashes remain explicit above.

On this rebased tree, all 560 native tests passed again, zero failures or ignored tests. Full staticAnalysis and all 26 selected JVM tests passed in 5m16s, with no failures, errors or skips. Architecture health and locked Cargo metadata passed. Logs use `/tmp/std08-publish-final-*.log` and `cargo-metadata.json`.

Workspace Clippy returned 101: `queries overflow the depth limit` while computing the layout of `client::connect_reality()` in `ripdpi-xhttp`. The single-package `cargo clippy -p ripdpi-xhttp --locked --all-targets -- -D warnings` reproduced the same error. The entire XHTTP crate, workspace manifest/lock/toolchain, .cargo/config.toml and clippy.toml have no diff from published origin/main. Logs: `/tmp/std08-publish-final-workspace-clippy.log` and `/tmp/std08-xhttp-triage-xhttp.log`. This published-source blocker does not replace the earlier passing workspace result. No recursion limit, gate, baseline or dependency was changed to hide it. Remote CI and any XHTTP repair remain outside this STD-08 repair evidence.

The exact affected-crate Clippy passed in 44.84s: `cargo clippy --locked -p ripdpi-packets -p ripdpi-desync -p ripdpi-tunnel-intercept -p ripdpi-android-platform-adapter -p ripdpi-diagnostics-runner -p ripdpi-android --all-targets -- -D warnings`, with the same build-gate, sccache and task target. Log: `/tmp/std08-xhttp-triage-std08.log`. This is scoped lint evidence, not a passing workspace gate. All native processes ended and no source or compiler limits were changed.
