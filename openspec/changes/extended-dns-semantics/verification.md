---
task_id: DGN-1791527373647141
change: extended-dns-semantics
commit_sha: null
local: passed
local_evidence: 1612 diagnostics tests, 18 app tests, 607 Rust tests, staticAnalysis, app/service locale lint, architecture gates, locked Cargo metadata, API snapshot verification and independent review passed on 2026-10-09.
remote_ci: required
remote_ci_evidence: null
device: blocked
device_evidence: adb devices -l returned no connected devices.
artifact: blocked
artifact_evidence: assembleGithubFullDebug is blocked by the missing native/xray/artifacts/libxray.aar producer artifact; no APK was produced.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DNS-COLLECT | DGN-1791527529890564 | 607 Rust tests across six affected crates; four existing opt-in or flaky tests ignored | Passed |
| REQ-DNS-EXPLAIN | DGN-1791527530677663 | Full diagnostics suite: 1612 tests, zero failures/errors/skips | Passed |
| REQ-DNS-EXPLAIN | DGN-1791527531937583 | 18 app tests, current/history mapper regression, Compose wrapping/RTL renders and ten-locale lint | Passed |
| REQ-DNS-EXPORT | DGN-1791527532924728 | Legacy JSON, bounded metadata and redacted JSON/text tests; staticAnalysis and architecture gates | Passed |

## Observed evidence

- Regression tests failed before the fixes for CNAME-owner addresses, negative SOA TTL scope, native parser-error classification, archive metadata redaction, DNS tool uncertainty and the canonical native probe type in UI. They pass in the combined checks.
- Native parser tests cover A/AAAA query binding, negative answers, CNAME chains, malformed packets, response flags, TTL and EDE codes. Existing runtime resolver validation remains strict.
- Current and saved results use the same response projection. LTR and Arabic RTL Compose renders were inspected. Long fields wrap without horizontal clipping.
- Independent review found no remaining P1/P2 issues. The final parser-error classification change received a second review.
- Architecture health: 21 existing indicators, zero new or worsened entries. Native architecture contracts: zero violations. Locked Cargo metadata resolves.
- The Rust public API snapshot was generated with cargo-public-api 0.52.0 from locked nightly rustdoc JSON through the owning snapshot script, then checked. No dependency, schema version or golden fixture changed.
- The four ignored Rust tests predate this feature: two timing-sensitive trigger-fuzzing fixtures and two opt-in soak tests. They are not claimed as passing.

## Combined local commands

```sh
./gradlew :core:diagnostics:testDebugUnitTest :app:testGithubFullDebugUnitTest \
  --tests '*DiagnosticsDnsResponse*' \
  --tests '*DiagnosticsUiFactoryLocalizationContractTest*' \
  --tests '*DiagnosticsUiContextSupportTest*' \
  --tests '*HistoryConnectionDetailUiFactoryTest*' \
  staticAnalysis :app:lintGithubFullDebug :core:service:lintDebug \
  -Pripdpi.skipNativeBuild=true --max-workers=2 --offline

cargo test --locked --manifest-path native/rust/Cargo.toml \
  -p ripdpi-monitor-engine -p ripdpi-diagnostics-contracts \
  -p ripdpi-diagnostics-classification -p ripdpi-diagnostics-dns \
  -p ripdpi-diagnostics-runner -p ripdpi-ech-dns
```

Gradle result: BUILD SUCCESSFUL in 4m 30s. Rust result: 607 passed, zero failed, four existing ignored tests.

## Workspace lint and Android artifact

`cargo clippy --locked --workspace --no-deps --all-targets -- -D warnings` passed.

`./gradlew :core:engine:buildRustNativeLibs --max-workers=2 --offline` passed in 2m 29s with the default local ARM64 ABI, including the changed diagnostics and Android bridge crates. This proves native library compilation, not APK packaging or device behavior.

`./gradlew :app:assembleGithubFullDebug --max-workers=2` failed at `:core:engine:verifyLibXrayArtifacts`: the required `native/xray/artifacts` producer directory does not exist. No compatible AAR was available in the registered checkouts. The packaging gate was kept intact; no APK is claimed. The first offline attempt also found an uncached JaCoCo build dependency; retrying online resolved that dependency.

## Remaining acceptance lanes

Hosted CI and physical-device behavior have not yet been observed. Resolver AD/EDE flags are claims from the response; they are not local DNSSEC verification or proof of provider interference. The current scan still issues A queries; standalone IPv6 or NAT64 reachability is outside this change.
