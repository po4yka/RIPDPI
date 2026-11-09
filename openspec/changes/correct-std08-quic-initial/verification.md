---
task_id: DGN-1794211297803022
change: correct-std08-quic-initial
commit_sha: 49edcd11a848fb2050ef1df0a0985e5e0392dd71
local: required
local_evidence: "The rebased pre-IP candidate passed packets 308+5, desync 159+7, TUN 60, adapter 5+2, strict rustls and facade 11, workspace Clippy and ci-gates. The final IP correction passed red-to-green unit and independent wire/TLS checks for 18 cases. Its full repeated suite passed: packets 309+5, desync 159+7, TUN 60, adapter 5+2, independent oracle 1 (18 cases), facade 11; 559 total, no failures or ignored tests. Workspace Clippy and ci-gates passed. JVM 25 passed. staticAnalysis has four unchanged pre-existing diagnostics issues."
remote_ci: required
remote_ci_evidence: Pending the authorized push.
device: required
device_evidence: Final IP-target source 49edcd11a passed all three minified ARM64 API 37 methods with real JNI, zero failures or skips. Each method was required exactly once, total three. Actual R8 and the 41-export native inventory passed. No physical device or external server acceptance is claimed.
artifact: not_applicable
artifact_evidence: No release or published binary is owned by this repair.
deployment: not_applicable
deployment_evidence: No deployment is owned by this repair.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-QUIC-ACTIVE-HELLO | DGN-1794211430951611 | eea831e69 and 49edcd11a: strict independent rustls HP/AEAD and TLS acceptance for three profiles, both versions and DNS/IPv4/IPv6 targets (18 cases); IP SNI absence asserted separately; only TLS 1.3 offered | Passed locally |
| REQ-QUIC-RESPONSE-INTEGRITY | DGN-1794211431545271 | Native RFC Initial/Retry and VN regressions passed; 6ff2021c6: Kotlin orchestration, all 11 JNI facade tests, approved actual ELF export inventory and all three minified emulator methods passed | Passed locally |
| REQ-QUIC-INITIAL-TOKEN | DGN-1794211432222833 | Two token regressions passed; independent v1/v2 wire equality; fix integrated as c880ad4b0 | Passed locally |
| REQ-QUIC-INITIAL-ACK | DGN-1794211432765485 | fcf3090c4: three ACK regressions, independent v1/v2 vectors and malformed ranges; independent review clear | Passed locally |
| REQ-QUIC-DATAGRAM-CRYPTO | DGN-1794211433260336 | 95130ecd6: packet and desync suites passed; four independent TShark captures accepted v1/v2 coalescing and short final packets; marker offsets checked | Passed locally |
| REQ-QUIC-HELLO-COMPLETE | DGN-1794211433822597 | 54ee1b0e2: strict completeness regressions passed in combined 284 unit and five integration tests; independent boundary review clear | Passed locally |
| REQ-QUIC-PROBE-CANCEL | DGN-1794211434635760 | F7 transport5 and probe14 JVM tests passed with no skips; loopback port rebind and bounded DNS admission; independent correction review clear | Passed locally |

## Scope

Audit baseline: a7e8417a4baa2b96919f003f218884ab0da6d9d4. Native combined checks use integration base 7bd65bae644a250a6d00c4207426be1fdffa8607 and candidate HEAD 7f356e8c86c5dcac2cb68a2484add155d9fda14d plus the staged F2 patch (SHA-256 9234d7cb372ac3f3dd71f4845020c92f4e79d6587894285af7c5decb7612d5c6). The preceding audit observed the failures on the same STD-08 source tree. C, local V, selected RFC S and external E remain separate. No external E is claimed. Do not archive until required evidence is resolved.

## JNI export approval

The new validation export needs one inventory addition. JNI_CONTRACT.md:585-588 requires an explicitly approved regeneration of jni-symbols.baseline. Source and tests passed independent review. NDK 29 llvm-nm extracted the actual ARM64 release library exports: 40 to 41, with only Java_com_poyka_ripdpi_diagnostics_dpi_NativeQuicInitialPacketBindings_jniValidate added and no removals. The user explicitly approved this exact inventory regeneration on 2026-11-09. The inventory was regenerated from the actual library with the owning NDK llvm-nm procedure. `.github/scripts/check-jni-symbols.sh` passed against that library.

Minified local acceptance uses the release build type with a temporary init script that selects the disposable debug signing key. No production signing material, physical device or external probe is used.

## Combined local checks

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

## Current integration candidate and F1 IP-target variant

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

The next published base, 3bc146510, includes separate repairs for the four original staticAnalysis violations. It does not change the QUIC generator, JNI bindings, Kotlin probe or R8 target rules. QUIC crypto/JNI dependency versions, sources and checksums (AES/AES-GCM, HKDF, SHA2, ring, rustls, jni and jni-sys) also match the tested integration base. The final rebase and its affected source, native and static checks remain pending; no newer binary runtime is claimed by the 49edcd11a evidence.
