---
task_id: DNS-1793945565828575
change: kotlin-dns-library-codec
commit_sha: af35730013804a98ddd0dd075bac1d031a2852ba
local: passed
local_evidence: All 7614 affected unit tests, full staticAnalysis, R8, ci-gates, locked Cargo metadata and independent review passed. Fresh tests, R8 and staticAnalysis passed after rebase onto da1d00518.
remote_ci: required
remote_ci_evidence: Pending integrated main push and hosted checks.
device: passed
device_evidence: Minified Github Full release APK installed and launched on the leased API 35 arm64 emulator. DNS survey completed with 19 of 21 resolvers responding; UDP and DoH returned 5 of 5 answers. DNS response details showed RCODE 0, TTL 102 and TC false. No crash log entries. This is emulator smoke evidence, not physical-device or VPN-route acceptance.
artifact: passed
artifact_evidence: Github Full release APK assembled with verified native runtimes; R8 and the desktop-provider discard check passed.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-KDNS-WIRE | DNS-1793945680585019 | diagnostics-data 87 tests; diagnostics 1662 tests; independent review | Passed |
| REQ-KDNS-TCP | DNS-1793945681167350 | DirectDnsTransportTest: real loopback UDP/TCP exchange, large datagram and question rejection | Passed |
| REQ-KDNS-PROBES | DNS-1793945681717226 | Service 2062 tests; app 3413 tests; diagnostics HTTPS/DoQ regressions | Passed |

## Local observations

- The pre-change reproduction accepted a query packet as a response. DirectDnsTest failed before the replacement.
- Independent review found and then verified fixes for high-bit TTL/OPT loss in dnsjava and false DoQ success on truncated messages.
- The first complete service run had one timeout in the unchanged PluggableTransportSocksBridgeWireTest. A complete rerun with two Gradle workers passed all 2061 service tests, and the next combined run passed again. No assertion or timeout was changed.
- Native dependencies built after removing the ambient CARGO_BUILD_JOBS override. The missing pinned NaiveProxy artifact was fetched with the repository script and SHA-256 verification.

## Final local commands

- `build-gate -- env -u CARGO_BUILD_JOBS ./gradlew :core:diagnostics-data:testDebugUnitTest :core:diagnostics:testDebugUnitTest :core:service:testDebugUnitTest :app:testGithubFullDebugUnitTest staticAnalysis -Pripdpi.skipNativeBuild=true --continue --max-workers=2 --console=plain`: exit 0. This is the repository CI mode for JVM tests; no device or release artifact claim is made.
- Unit reports: diagnostics-data 87, diagnostics 1662, service 2062, app 3413; zero failures, errors or skipped tests.
- `build-gate -- just ci-gates`: exit 0 after rebase. Architecture health has no new or worsened indicators; source contracts and API snapshots match.
- `cargo metadata --manifest-path native/rust/Cargo.toml --locked --no-deps --format-version 1`: exit 0.
- `git diff --check` and task validation passed. Independent review accepted both protocol corrections; final Detekt simplification preserves resolver outcomes.

## IP parser integration

- Rebased onto e08d09e5a and preserved both dependency entries. DirectDns now uses the shared NumericIp parser for server addresses and literal fast paths.
- Independent review confirmed that the IP/CIDR migration, DoQ classification, DNS matching and TCP fallback remain intact.
- The same combined test/staticAnalysis command passed on the merged code: 7224 tests, zero failures, errors or skips. CI source gates and locked metadata passed again.

## Final integration base

- Rebased onto 4a346b9e5. This added only the upstream Rust HTTP decoder correction and task records; app/core/Gradle inputs are byte-identical to the fully tested tree.
- `ci-gates`, architecture health and locked metadata passed on this base. Kotlin test and staticAnalysis results remain valid for unchanged inputs.

## Android dependency packaging

- The release R8 check exposed absent Lombok annotations, the SLF4J binder and the desktop Java 8 DNS SPI interface. The build now provides compile-only annotations and a no-op logging binding, excludes desktop SPI descriptors and checks that R8 discards the Java 8 provider.
- Independent review accepted the dependency and shrinker configuration. The complete combined JVM test and static-analysis command passed again: 7224 tests, zero failures, errors or skips.
- Full APK assembly initially stopped because the worktree lacked the required Xray AAR. The `xray-native` artifact from successful base CI run 42181223749 passed the repository release verifier for all four ABIs, including its source provenance and SHA-256.
- The first discard check failed because AGP's default service merge wildcard took priority over exact exclusions. After replacing that wildcard with the six Android service paths, the merged resource JAR contains neither desktop DNS descriptor and preserves all six Android descriptors byte for byte. Independent review accepted this packaging rule.

- Final command: `build-gate -- env -u CARGO_BUILD_JOBS ./gradlew :app:assembleGithubFullRelease staticAnalysis -Pripdpi.nativeAbisOverride=arm64-v8a -Pripdpi.nativeCpuBudget=2 -Pripdpi.enableAbiSplits=false --max-workers=2 --console=plain`: exit 0, 7m 32s. The complete release artifact includes real native runtimes; no skip-native flag was used.

- Release emulator smoke: installed and launched on RIPDPI_Pool_1 (API 35, arm64). Diagnostics -> Tools -> Technical details -> DNS availability -> Run DNS survey completed with 19/21 resolvers responding. Cloudflare UDP, Google UDP and Cloudflare DoH each returned 5/5. Expanded Cloudflare facts for EXAMPLE.COM showed an A address answer, RCODE 0, TTL 102 and TC false. The crash buffer was empty. The app was stopped and the pool lease released.

## Shadowsocks integration base

- Rebased without conflicts onto da1d0051866644cc29777c55bef5cb0701fad647. Upstream changed Shadowsocks validation in app, runtime-state and service, plus its Rust implementation. DNS and Android dependency packaging inputs are unchanged.
- Fresh command: `build-gate -- env -u CARGO_BUILD_JOBS ./gradlew :core:data:runtime-state:testDebugUnitTest :core:diagnostics-data:testDebugUnitTest :core:diagnostics:testDebugUnitTest :core:service:testDebugUnitTest :app:testGithubFullDebugUnitTest :app:minifyGithubFullReleaseWithR8 staticAnalysis -Pripdpi.skipNativeBuild=true --continue --max-workers=2 --console=plain`: exit 0, 13m 13s.
- Unit reports: runtime-state 387, diagnostics-data 87, diagnostics 1662, service 2063, app 3415. Total 7614; zero failures, errors or skips. R8, staticAnalysis, CI source gates, architecture health and locked metadata passed on the combined tree.
- The installed release APK and emulator observations above precede this rebase. The final combined tree has fresh host-test, static-analysis and R8 evidence; the changed upstream native runtime was not retested on the emulator in this DNS task.
