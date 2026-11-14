---
task_id: ENG-1794637889757963
change: fix-dpi17-audit-findings
commit_sha: a33c8d3afa9ffcc9c5a40bd6430700e4ad965258
local: passed
local_evidence: Final a33c8d3afa tree passed 796 native tests, enabled v1/v2 wire, six-crate Clippy, locked metadata, just ci-gates, 87 Kotlin/JVM tests and app/service Android lint. XML confirms zero failed, errored or skipped JVM tests and zero Error/Fatal lint findings. Final build-logic suite passed 53 tests and strict Android OBFS4 cross-builds passed four ABIs.
remote_ci: blocked
remote_ci_evidence: Full push CI 43583732581 failed only API35 Xray acceptance and its aggregate; the test expected obsolete core version 26.3.27 although the pinned runtime starts as 26.11.9. CodeQL 43583732472 passed all three languages with zero new results. Both stale test assertions are corrected locally; a successful full CI and CodeQL run on the correction are still required.
device: not_applicable
device_evidence: Physical-device and root acceptance were not requested. CI emulator checks are recorded under remote_ci; external QUIC peer compatibility is not claimed.
artifact: passed
artifact_evidence: Exact-a33 CI artifact 14109564274 passed strict source mode; its GitHub ZIP digest, source and four ABI patch identities, actual launcher/upstream payload hashes, Go 1.27.4, CGO and empty Lyrebird linker flags were verified. Durable receipts are in /Users/npochaev/Downloads/RIPDPI_network_audits/DPI-17-evidence-20261114/ci/.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DUMMY-COUNT | ENG-1794638159294108 | Full native owning suites and 87 Kotlin JVM tests, including boundary and direct-model cases | Passed |
| REQ-DUMMY-BYTES | ENG-1794638159294108 | v1/v2 direct counts and oversized host/output tests in desync suite | Passed |
| REQ-DUMMY-COMPAT | ENG-1794638159294108 | Full owning native suites and production loopback original byte equality; local scope only | Passed |
| REQ-DUMMY-UI | ENG-1794638174316919 | Ten locales, export/parity, standalone Kotlin lint and both Android lint targets | Passed |
| REQ-DUMMY-WIRE | ENG-1794649284222091 | Correctly padded authenticated v1/v2 seeds and enabled production loopback regression passed twice | Passed |
| REQ-DUMMY-ASSET | ENG-1794641528622974 | 53 build-logic tests, four local strict ABI builds and exact-SHA CI source/artifact receipts with actual payload hash verification | Passed |
| REQ-DUMMY-ACCEPT | ENG-1794641528622974 | Owning JVM/lint gate and exact-SHA CI/CodeQL checks | Required |

## Observed local checks

Run Cargo from native/rust with pinned Rust 1.100.0 and --locked.

- cargo test -p ripdpi-desync -p ripdpi-proxy-config --locked: 389 passed, zero failed, ignored or filtered. Log: /tmp/dpi17-combined-native-tests-final.log.
- RIPDPI_RUN_WIRE_E2E=1 cargo test -p ripdpi-proxy-runtime --locked --test wire_e2e udp_quic_chain_steps_emit_dummy_split_and_fake_version_packets -- --exact --nocapture: one test covering both versions passed. Seven unrelated tests filtered. Log: /tmp/dpi17-combined-native-wire-final.log.
- cargo clippy -p ripdpi-config -p ripdpi-desync -p ripdpi-proxy-config -p ripdpi-proxy-runtime --locked --all-targets --no-deps -- -D warnings: passed. Log: /tmp/dpi17-combined-native-clippy-final.log.
- CARGO_TARGET_DIR=/tmp/dpi17-api-target just ci-gates: passed, including the API snapshot gate. It listed 13 targets and skipped the Linux-owned runtime-platform snapshot on macOS. Log: /tmp/dpi17-ci-gates-isolated.log.
- The earlier shared-target API run reported a missing config constant in dependent crates despite a correct config snapshot. The isolated run passed without another source change. No cache root cause beyond these observations is asserted.
- Standalone ktlint 1.8.0 checked both changed Kotlin files after formatting: passed. All ten locale sets and the unchanged translation-key export passed their owning source checks.
- Independent read-only review found no remaining actionable defect. Pre-commit and commit-message hooks passed for all three fix commits.

## Observed completion checks

- `build-gate --lane ripdpi -- env -u CARGO_BUILD_JOBS ./gradlew :core:data:model:testDebugUnitTest :app:lintGithubFullDebug :core:service:lintDebug -Pripdpi.skipNativeBuild=true --max-workers=4 -Pripdpi.nativeCpuBudget=4`: passed in 6m30s, 623 tasks (419 executed, 204 from cache). Model XML: 19 suites, 81 tests, zero failures, errors or skips. Both lint targets completed with zero Error/Fatal findings. App reports 4097 warnings and four hints; service reports 17 warnings. Log: /tmp/dpi17-completion-gradle.log. XML/HTML reports are under the owning modules in the completion worktree.
- The [CI run on 4ca0947874](https://github.com/po4yka/RIPDPI/actions/runs/43574051616) failed in the artifact-action pin assertion and the Lyrebird link step (`anet: invalid reference to net.zoneCache`). The observability composite uses the official upload-artifact v7.0.2 commit. The test now checks that same commit. All 45 CI contract tests passed: `python3 -m unittest scripts.tests.test_build_observability_contracts scripts.tests.test_measure_native_abi_parallelism scripts.tests.test_package_native_symbols scripts.tests.test_release_artifact_uploads scripts.tests.test_rust_native_task_layout scripts.tests.test_verify_jni_readiness_mapping`. Log: /tmp/dpi17-ci-contracts-green.log.
- `build-gate --lane ripdpi -- env -u CARGO_BUILD_JOBS ./gradlew -p build-logic :convention:test --max-workers=4 -Pripdpi.nativeCpuBudget=4`: 53 tests in 14 classes passed for the final three-file patch. Log: /tmp/dpi17-obfs4-buildlogic-test-final.log.
- Standalone ktlint passed for all four changed Kotlin/build-logic files. Log: /tmp/dpi17-buildlogic-ktlint.log.
- The final pinned upstream checkout passed commit, three preimage, patch and three postimage checks. Four actual Android binaries built with Go 1.27.4, CGO_ENABLED=1 and NDK 30.0.16248370 API 27 clang. Each command used empty GOFLAGS, `GOPROXY=off`, unchanged checksum verification, `-mod=readonly -p=4 -trimpath -buildvcs=false -ldflags='-s -w -buildid='`, the ABI-specific GOARCH and CC/CXX, and the package `./cmd/lyrebird`. Go's default linkname checks remained enabled. `go.mod` and `go.sum` did not change.
- Each Android dependency graph excluded anet and Pion. The graphs contained 228 packages for ARM32, ARM64 and x86, and 229 for x86_64. `file` and actual `go version -m` checked ELF architecture, GOOS=android, GOARCH and CGO_ENABLED=1.
- The real host binary passed managed startup on loopback: VERSION 1, CMETHOD obfs4 socks5 127.0.0.1, CMETHODS DONE; SOCKS5 greeting 050100 returned 0500; closing stdin produced exit 0. No remote peer was contacted.
- The first aggregate script exited 2 after the successful builds because its temporary smoke helper disappeared. The helper was restored in the isolated worktree. The remaining smoke and receipt checks then passed without a repeated build. Logs: /tmp/dpi17-obfs4-validate-final.log and /tmp/dpi17-obfs4-managed-receipts-final.log. Scripts and generated receipt: /tmp/ripdpi-dpi17-obfs4-build-20261114/build/verification/dpi17-obfs4/. This receipt proves local cross-builds; it is not a prebuilt asset bundle.
- Independent read-only review of the final patch found no actionable critical or warning findings. No compiler check, baseline, golden fixture, dependency pin or workflow security control was weakened.

The reviewed source-patch manifest SHA-256 is `779126470ad7064ee73b55ea7a66079615d4e97d33a2bec20d8068fd54e6d8a1`. Local binary SHA-256 values:

| ABI | SHA-256 |
|---|---|
| armeabi-v7a | 350319317b8665020bf4da20a52889004fd9c723ad175eca94481281710ef5d3 |
| arm64-v8a | 333ac32f39c6127ad8cf2c8106662d012c71fb6e28c8411310ae2cdfb9f9744b |
| x86 | 75cad16e884e2aed4813bc115a2ebea58a780580a941e2ce1a5088e4cddbea22 |
| x86_64 | 6866c3b73e478eb3b47c389b9c118ba44d1ced14a0524166d569ee2b7e94ff98 |

## Post-rebase validation and workspace recovery

On c5eee724928b9fc529a2a888e3d7cb6621bf41bf, owning native suites passed 785 tests with zero skipped; doctests had zero tests; six-crate Clippy passed. The enabled exact wire test failed: one datagram arrived instead of four. The shared seed appears shorter than the new production 1200-byte minimum. Keep that production gate; repair the shared test seed and repeat the wire check.

The exact combined JVM/lint command also passed after rebase in 2m53s (623 tasks: 152 executed, 10 from cache, 461 up-to-date). Matching JUnit and lint XML could not be read after the worktree disappeared. The command success is preserved in /tmp/dpi17-completion-gradle-rebased.log; pre-rebase counts are not post-rebase counts.

The parent observed exit 0 from the full `just ci-gates` command on this tree with an isolated API target. Its log and native logs were lost with the worktree. A new persistent worktree at /Users/npochaev/GitHub/RIPDPI-worktrees/dpi17-completion-final-20261114 was prepared through `just setup` and `just checkout-doctor`. It retains the committed changes. Required acceptance remains open until the seed repair and final checks finish.

## Final seed correction and persistent local results

The shared seed counted the four packet-number bytes twice. A new owner test failed with actual length 1196 versus expected 1200 before the correction. The [RFC 9000 section 14.1](https://www.rfc-editor.org/rfc/rfc9000.html#section-14.1) minimum applies to the UDP payload carrying a client Initial. The fix removes the repeated packet-number term. It does not relax production validation. The regression checks the literal size and independent authenticated parser results for v1/v2, version, SNI and complete CRYPTO.

After the fix, the new regression passed both variants; the exact enabled wire test passed twice (one test passed, seven unrelated tests filtered each time). Four full owning suites passed 786 tests with zero skipped. Six-crate Clippy and cargo fmt passed. The independent reviewer found no actionable issue and checked other seed callers. Logs and exact commands: /Users/npochaev/GitHub/RIPDPI-worktrees/dpi17-wire-seed-20261114/build/verification/dpi17-wire-seed/.

On b8f1338dd73cf22e7691e28e9760af180bca878c, the exact combined JVM/lint gate passed in 4m17s (623 tasks: 535 executed, 88 from cache). Matching XML reports 19 suites, 85 tests, zero failures, errors or skips; all seven StrategyChainValidationTest cases passed. App lint has zero Error/Fatal findings, 4097 warnings and four hints. Service lint has zero Error/Fatal findings and 17 warnings. Persistent copies: /Users/npochaev/GitHub/RIPDPI-worktrees/dpi17-completion-final-20261114/build/verification/dpi17-android/.

`build-gate --lane ripdpi -- env CARGO_BUILD_JOBS=4 CARGO_TARGET_DIR=/Users/npochaev/GitHub/RIPDPI-worktrees/dpi17-completion-final-20261114/build/verification/dpi17-final/api-target just ci-gates` passed on the root tree with the seed fix. The API gate checked 13 targets and skipped the Linux-owned runtime-platform snapshot on macOS. Log: build/verification/dpi17-final/ci-gates.log in the persistent completion worktree.

## Delivered source validation

The final source SHA is a33c8d3afa9ffcc9c5a40bd6430700e4ad965258. It includes the preserved upstream main changes through cf37576cad54e939144bec871706d5f2160445da. The source commits are:

- 90e12607c0: correct the artifact-action pin contract test.
- f66af6cce3: repair the pinned OBFS4 build with verified source-patch provenance.
- a33c8d3afa: correct shared QUIC Initial seed padding and add its regression.

The final gated script ran source gates, locked metadata, all four owning native suites, doctests, enabled exact wire, six-crate Clippy and the combined JVM/lint command in sequence. All commands passed. Native suites: 796 passed, zero skipped; doctests: zero tests. The wire test passed for both versions (one test passed, seven unrelated tests filtered). JVM XML: 19 suites, 87 tests, zero failures, errors or skips. Both Android lint targets have zero Error/Fatal findings; app has 4097 warnings and four hints, service has 17 warnings. Gradle completed in 2m02s, 623 tasks (143 executed, four from cache, 476 up-to-date).

Commands, source SHA, full logs, metadata and parsed XML summary are retained under /Users/npochaev/GitHub/RIPDPI-worktrees/dpi17-completion-final-20261114/build/verification/dpi17-final/rebased/. The script is its parent directory's final-checks.sh. Source gates and API snapshots passed with the isolated API target. The main checkout and origin/main both reached this SHA after the authorized fast-forward and push.

Hosted checks for this source are [CI 43583732581](https://github.com/po4yka/RIPDPI/actions/runs/43583732581) and [CodeQL 43583732472](https://github.com/po4yka/RIPDPI/actions/runs/43583732472). Acceptance remains open until both complete successfully and the generated asset provenance is checked.

## Hosted artifact provenance

The strict asset job 132414247342 in [CI 43583732581](https://github.com/po4yka/RIPDPI/actions/runs/43583732581) passed on exact source a33c8d3afa9ffcc9c5a40bd6430700e4ad965258. Published artifact 14109564274 was checked against GitHub's ZIP SHA-256 `93cea3bca2b8ae8199f1e9b5fe5b947e69046e24046afd02f8db05416b02aed9`.

The generated manifest SHA-256 is `bb50a1095359c758f22245c729db3baa485e04e2f4cee184c885a4bcb10030c6`. Its Lyrebird source and all four ABI records use upstream pin 59ac7e400c384773f5584dbe366516ad04a33d9f, patch manifest 779126470ad7064ee73b55ea7a66079615d4e97d33a2bec20d8068fd54e6d8a1, Go 1.27.4, CGO enabled, empty extra linker flags, source mode and no build error. The actual launcher and upstream binary payloads were streamed from the archive and their SHA-256 values matched all receipts. No executable was installed or run from this CI artifact.

The raw manifest, GitHub artifact metadata and verification result are retained under /Users/npochaev/Downloads/RIPDPI_network_audits/DPI-17-evidence-20261114/ci/. Local build/verification directories later disappeared from both persistent worktrees; their removal cause was not established. The local command results and counts in this record were observed before that loss. Those historical local paths no longer promise retained files. Hosted evidence uses the durable Downloads directory and GitHub run links.

## CodeQL source result

[CodeQL 43583732472](https://github.com/po4yka/RIPDPI/actions/runs/43583732472) completed successfully on exact a33c8d3afa9ffcc9c5a40bd6430700e4ad965258 for Rust, Java/Kotlin and Actions. Actual analyses have empty error fields: Rust 2167146681 has 39 results, Java 2167126244 has three, Actions 2167085610 has zero. SARIF comparison by rule, path and message against successful source 4f0d5e827900f125583c0fb8ebccbd349a7bc0c7 found zero added and zero removed results in each language. The immediate cf37576cad analyses had unsuccessful-execution errors and could not serve as clean baselines.

The open-alerts API returned zero; this does not turn the 39 plus three SARIF results into zero results. Existing Java results concern local authentication, SSRF and test-resource ZipSlip paths outside this change. Actual current and baseline SARIFs, analysis metadata and a33-codeql-verified-summary.json are retained under the durable audit evidence ci directory.

## API 35 Xray acceptance correction

The full push CI 43583732581 completed with 70 jobs: 50 succeeded, 18 were skipped and two failed. The substantive failure was API35 instrumentation job 132421165055; the aggregate ci-required then failed. The other four API jobs passed. A separate scheduled same-SHA run had only seven successful jobs and 49 skips and does not replace the full push run.

The selected real Xray provider suite ran five tests: four passed, one failed, zero errors or skips. `bothTransportsRouteDistinctUidTrafficThroughRealTunAndRestart` failed at its version assertion after distinct-UID traffic, owned DNS and listener-bound checks. It required 26.3.27, but hosted logcat records `core: Xray 26.11.9 started`.

The [pinned Xray-core 676eaf66f8f0 core/core.go](https://github.com/XTLS/Xray-core/blob/676eaf66f8f0/core/core.go) defines core.Version() as 26.11.9. Pinned libXray returns that value through typed XrayVersionResponse; the linked Kotlin bridge correctly reads its version field. The module pseudo-version's 260327 prefix is not the current runtime version. Both obsolete assertions in XrayProviderE2ETest are corrected through one expected-version constant and assertEquals, which reports the actual value on failure. No version check, TUN check or scenario is removed.

The corrected AndroidTest source compiled and app lint passed locally in 9m25s (627 tasks: 484 executed, 143 from cache). Matching lint XML has zero Error/Fatal findings, 4096 warnings and four hints. Standalone ktlint and just ci-gates passed. Durable logs: xray-version-local-gradle.log and xray-version-ci-gates.log in the audit evidence directory.

Actual snapshot version was not printed by the original failed assertion, so a further null/unknown issue remains unexcluded until the corrected five-method API35 suite passes. Durable failure artifacts and source trace: /Users/npochaev/Downloads/RIPDPI_network_audits/DPI-17-evidence-20261114/ci/a33-android35-xray/ and a33-xray-version-triage.md in its parent directory.

## Previous blocked validation

The exact combined Gradle command was ./gradlew :core:data:model:testDebugUnitTest :app:lintGithubFullDebug :core:service:lintDebug --offline -Pripdpi.skipNativeBuild=true. It failed before test/lint execution because protobuf Gradle plugin 0.10.1 was not cached. Earlier online lint blocked on Maven/Plugin Portal connections; bounded official endpoint attempts timed out. Log: /tmp/dpi17-combined-android-offline.log.

A bounded actual-source kotlinc 2.4.21 fallback also failed before JUnit execution: generated protobuf code required a newer runtime than cached protobuf-java 3.25.5, and the required IPAddress artifact was absent. No stubs, source stripping, dependency changes or weakened checks were used. Commands and logs: /tmp/ripdpi-dpi17-kotlin-validation/README.md. This fallback does not replace the Gradle gate.

Rerun the exact Gradle command without --offline once official dependency access is restored. Device, root, external QUIC peer, DPI efficacy and visual acceptance are not claimed. Official dependency access recovered during completion. This record is not archive-ready until the resumed local and hosted acceptance checks pass.

## Delivered fixes

- e87f2e02241c4097647bf02b7103a34b148ede4a: bound resource use.
- 147c98b5b87dda276d000019458a722538113239: correct all locale descriptions.
- 1cf170c07a14a0d9a3ee6183dbcb53e3e64f5f27: repair wire regression.
