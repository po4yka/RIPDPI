---
task_id: DGN-1791536817237300
change: active-pmtu-diagnostics
commit_sha: null
local: passed
local_evidence: 714 Rust tests plus four process checks, 1642 diagnostics tests, six app tests, three catalog tests, staticAnalysis, app/service lint, ARM64 native build, workspace Clippy, dependency policy, architecture, API and fixture gates passed.
remote_ci: required
remote_ci_evidence: null
device: blocked
device_evidence: adb devices returned no connected devices; Android device and provider-path acceptance remain unverified.
artifact: blocked
artifact_evidence: ARM64 Rust libraries built successfully; verifyLibXrayArtifacts failed because native/xray/artifacts is missing, so APK packaging is blocked.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-PMTU-MEASURE | DGN-1791537052566328 | Real local QUIC clear-path and 1200/1300-byte size-cliff tests for IPv4 and IPv6 | Passed |
| REQ-PMTU-LIMITS | DGN-1791537052566328 | TLS/ALPN, no-fragment capability, ceiling, mapped-address and inconclusive-result tests | Passed |
| REQ-PMTU-LIFETIME | DGN-1791537052566328 | Cancellation, absolute deadlines and bounded socket protection tests; independent async review | Passed |
| REQ-PMTU-CONTRACT | DGN-1791537065009386 | Complete diagnostics suite: 1642 tests, including scope revocation, redaction, plans and persisted evidence | Passed |
| REQ-PMTU-UI | DGN-1791537067362894 | Six app tests; four inspected English and Arabic RTL renders at 360 dp, including 1.5 font scale | Passed |

## Measurement scope

The manual profile and full Home analysis run separate IPv4 and IPv6 QUIC DPLPMTUD observations. The socket must prevent fragmentation. TLS trust, hostname and h3 ALPN are verified. HTTP/3 control streams and SETTINGS run during observation; the probe sends no HTTP request or application body.

A result reports the current ACK-confirmed outbound UDP payload lower bound, probe/loss counters, configured ceiling and observation completion. The initial 1200-byte size is not a measured lower bound. The ceiling is not an exact path MTU, and the result does not establish the reverse-path limit or a provider restriction. QUIC failure remains inconclusive. In-path proxy execution is unsupported, without a direct fallback.

The absolute scan/probe deadline bounds DNS, protection, handshake and observation. Cancellation keeps partial facts without a healthy final result. Network changes revoke result authority. Redacted exports remove peer addresses and unknown fields. Quick and background runs do not include this active probe.

## Native validation

The six affected Rust crates passed 714 tests with eight pre-existing ignored external-network or opt-in tests. Four socket child-process checks also passed. Tests use real local QUIC/HTTP3 peers and controlled packet-size cliffs, not synthetic success outcomes. No feature test was disabled.

Workspace Clippy passed with all targets, the three await_holding lints and warnings denied. Cargo dependency policy, locked metadata, formatting, API snapshot checks, native architecture contracts and architecture health passed. Architecture health reported no new or worsened indicators.

## Review and fixture evidence

Independent implementation, Kotlin contract and async cancellation reviews found no open correctness findings. The review prompted a boundary assertion for equal sent/lost probe counts. No unsafe code or JNI contract change was added. No production dependency was added; an existing workspace fixture helper is a dev-dependency only.

The specialist regenerated only the two shared catalog and taxonomy fixture families authorized by the user. The changes add the PMTU profile and outcome cases. Read-only fixture checks passed: 24 tests. No quality baseline changed.

The UI review inspected English and Arabic RTL at 360 dp with enlarged text. Labels, values, direction limits and scroll content remain readable without overlap. All 21 new string keys exist in the ten supported locales.

## Android-side validation

`staticAnalysis`, `:app:lintGithubFullDebug`, `:core:service:lintDebug` and the complete diagnostics suite passed together in 8m 5s. The final diagnostics run passed all 1642 tests, including the equal sent/lost counter boundary. The six PMTU app tests passed separately. JVM checks use `-Pripdpi.skipNativeBuild=true`; native compilation is a separate gate.

The first full lint run waited on external Maven metadata. The same gates then ran offline with cached dependencies and local rules. Detekt found four complexity thresholds; small pure-function extractions fixed them. The complete gates and an independent Kotlin review passed after those changes. No rule or baseline was weakened.

## Artifact and integration boundary

The three `DiagnosticsCatalogPmtuTest` tests passed. `:core:engine:buildRustNativeLibs --offline` passed for the local arm64-v8a ABI in 3m 39s. `:core:engine:verifyLibXrayArtifacts` failed because `native/xray/artifacts` does not exist. This separate producer artifact is required for APK packaging. No APK or connected-device acceptance is claimed.

The integration branch was rebased onto c2baf9931 before the combined gates. A final fetch confirmed that origin/main had not advanced. The final independent diff review found no actionable issues. The task remains in review for remote CI, APK artifacts and device/provider-path acceptance.
