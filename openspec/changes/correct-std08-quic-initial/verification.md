---
task_id: DGN-1794211297803022
change: correct-std08-quic-initial
commit_sha: 857a1bf858b781db70d1b166f1753d2165b64d83
local: required
local_evidence: "Combined packets: 284 unit and five integration tests passed. Desync: 151 unit and seven integration tests passed. Adapter QUIC: seven tests passed. Strict rustls: six profile/version cases passed. JVM: 25 tests passed. Workspace Clippy and ci-gates passed. staticAnalysis failed on four unchanged pre-existing diagnostics issues; see details below."
remote_ci: required
remote_ci_evidence: Pending the authorized push.
device: required
device_evidence: GithubFullRelease with R8 on ARM64 API 37 passed all three requested methods, zero failures or skips. The owning validator required each method exactly once. Official RFC v1/v2 vectors, VN rejection and real malformed loopback responses passed through JNI. No physical device or external server acceptance is claimed.
artifact: not_applicable
artifact_evidence: No release or published binary is owned by this repair.
deployment: not_applicable
deployment_evidence: No deployment is owned by this repair.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-QUIC-ACTIVE-HELLO | DGN-1794211430951611 | 611221877: strict rustls QUIC acceptance for three profiles and both versions; only TLS 1.3 offered; API snapshot regenerated | Passed locally |
| REQ-QUIC-RESPONSE-INTEGRITY | DGN-1794211431545271 | Native RFC Initial/Retry and VN regressions passed; 857a1bf85: Kotlin orchestration, all 11 JNI facade tests, approved actual ELF export inventory and all three minified emulator methods passed | Passed locally |
| REQ-QUIC-INITIAL-TOKEN | DGN-1794211432222833 | Two token regressions passed; independent v1/v2 wire equality; fix integrated as 4416c34cc | Passed locally |
| REQ-QUIC-INITIAL-ACK | DGN-1794211432765485 | efe3f3429: three ACK regressions, independent v1/v2 vectors and malformed ranges; independent review clear | Passed locally |
| REQ-QUIC-DATAGRAM-CRYPTO | DGN-1794211433260336 | 3b19ad975: packet and desync suites passed; four independent TShark captures accepted v1/v2 coalescing and short final packets; marker offsets checked | Passed locally |
| REQ-QUIC-HELLO-COMPLETE | DGN-1794211433822597 | b552a4f84: strict completeness regressions passed in combined 284 unit and five integration tests; independent boundary review clear | Passed locally |
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

## Final frozen-candidate Android result

`GithubFullRelease`, ARM64, API 37, `google_apis`, `pixel_10_pro_xl`: all three requested tests passed, zero failures and skips. The owning JUnit validator required each named method exactly once and total count three. The temporary AVD was removed by its lease wrapper. No active build or device lease remains.

The source snapshot was HEAD 7f356e8c86c5dcac2cb68a2484add155d9fda14d plus staged patch fae3cb6ffeded0a6af5a8a01d36efc11c654427599eb81268a4971ad16113a62, now committed as 857a1bf858b781db70d1b166f1753d2165b64d83. The final app APK SHA-256 is 18ab145345d9fbe3cc187c19e6abf93345157865a257f81a40285a5018124bb7, test APK e84e440c4612f2dff3fc4aad2958f2ecd7c13c6af9bdcc2a25336f052677ffc5. Packaged native library SHA remains ea7c29b3f7e0402d04f3776d50fabfcd25c75f0aa746132313dbd795739a8c2d, with the approved 41-export inventory. This is local ARM64 acceptance with a disposable debug key and narrow instrumentation target member rules, not four-ABI or external interoperability acceptance.

Exact outer command:

```sh
python3 scripts/ci/android-avd-pool.py run --api 37 --arch arm64-v8a --target google_apis --profile pixel_10_pro_xl --temporary --reason 'Pool slots are missing config.ini; validate minified QUIC JNI with narrow instrumentation target rules' -- bash /tmp/ripdpi-std08-device-acceptance.sh
```

The inner command and all exact Gradle properties are preserved in `/tmp/ripdpi-std08-android-acceptance/exact-device-command.sh`. It runs `:app:connectedGithubFullReleaseAndroidTest` through `build-gate --lane ripdpi`, with the real pinned Xray AAR, native ABI override, temporary signing init script, exact test class and fresh single-use Gradle process. All ADB calls use the leased serial.

Preserved local evidence copies under `/tmp/ripdpi-std08-android-acceptance/final-frozen-artifacts/` include the two APKs, final JUnit XML and HTML report. `acceptance-summary.json`, `release-artifacts.json`, `r8-jni-evidence.txt`, `r8-test-evidence.txt`, and `junit-validation.log` record provenance, exact method counts, preserved native names and the corrected JVM void test signature. The two earlier failed attempts remain separate and are not counted as passes.
