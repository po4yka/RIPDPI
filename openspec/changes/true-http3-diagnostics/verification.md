---
task_id: DGN-1791532838740620
change: true-http3-diagnostics
commit_sha: null
local: passed
local_evidence: 702 Rust tests, 1633 diagnostics tests, 13 app tests, three catalog tests, rebased detection tests, staticAnalysis, app/service lint, ARM64 native build, workspace Clippy, dependency policy, architecture, API and contract checks passed.
remote_ci: required
remote_ci_evidence: null
device: blocked
device_evidence: adb devices returned no connected devices; real Android and provider-path acceptance remain unverified.
artifact: blocked
artifact_evidence: ARM64 Rust libraries built successfully; verifyLibXrayArtifacts failed because native/xray/artifacts is missing, so APK packaging is blocked.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-H3-PROTOCOL | DGN-1791533013645106 | Real local H3 server, TLS/ALPN and Initial rejection tests | Passed |
| REQ-H3-BOUNDS | DGN-1791533013645106 | Deadline, cancellation, body/header cap, protect and resource tests | Passed |
| REQ-H3-PRESENT | DGN-1791533014485292 | Request, catalog, stage and legacy Kotlin tests | Passed |
| REQ-H3-PRESENT | DGN-1791533015411877 | Current/history mapper, Compose, locale checks | Passed |
| REQ-H3-PRIVACY | DGN-1791533014485292 | Redaction, malformed evidence, admission and scope tests | Passed |
| REQ-H3-BOUNDS | DGN-1791533016622341 | Combined gates and independent/async review | Passed |

## Behavior and scope

The manual profile and full analysis measure a real HTTP/3 GET on the raw network path. The probe validates TLS trust and hostname, ALPN `h3`, final headers, bounded body data and stream completion. HTTP error responses confirm the protocol but remain attention results. Existing QUIC Initial probes retain their previous meaning. In-path execution returns unsupported without a direct fallback.

The probe uses one absolute deadline, a bounded DNS executor, at most four peers, at most eight informational responses, a 16 KiB header section limit and the configured body cap. A fixed protection worker and a four-job queue keep a slow platform callback off the probe runtime. Abandoned or expired jobs close their sockets without sending packets.

Current results, saved history, the stage scale and summaries use validated typed evidence. Malformed stage/progress combinations remain unknown. Network changes revoke outcome authority and mark retained facts unverified. Redacted exports remove peer addresses and unknown fields. Response bodies and header values are not stored.

## Review evidence

Implementation tests and independent review found three issues that were fixed before commit: premature body FIN against Content-Length, informational headers before the final response, and contradictory stored stage facts. The async audit also found the synchronous platform protection callback could exceed the deadline; the fixed bounded worker resolves it. Focused real HTTP/3 tests cover these cases. The final reviews found no open P1/P2 or missing cancel-safety contracts.

English and Arabic RTL renders at 360 dp, including 1.5 font scale, were inspected. The interface has 33 new resource keys in all ten locales. Tests cover current and historical mapping, partial responses, network scope warnings and private-field exclusion.

The user authorized the two shared diagnostics-contract-fixtures families in this conversation. The specialist observed the two expected fixture differences, generated only the catalog and taxonomy additions, inspected all differences and passed the read-only owning checks. No unrelated fixtures or quality baselines changed.

## Combined native validation

The six affected crates passed `cargo test --locked` with one test thread: 702 tests passed and eight pre-existing external-network or opt-in tests remained ignored. The two process-isolated socket tests also passed their child-process checks. No tests were disabled for this feature. The set includes contracts, HTTP, transport, runner, probes and monitor engine, including shared fixture coverage.

Workspace Clippy passed with `--locked --workspace --all-targets`, all three `await_holding_*` lints and `-D warnings`. Rust formatting, Cargo dependency policy (advisories, bans, licenses and sources), locked metadata, API snapshots, native architecture contracts and architecture health passed. Architecture health has no new or worsened indicators.


## Combined Android-side validation

`staticAnalysis`, `:app:lintGithubFullDebug`, `:core:service:lintDebug`, the complete diagnostics unit suite and the selected app UI suite passed together. The final run passed 1,633 diagnostics tests and 13 app tests. The three `DiagnosticsCatalogHttp3Test` tests passed separately in build-logic. An initial Home test failure was a stale skipped-stage count; its assertion now includes HTTP/3. The complexity gate required splitting transport and response validation into small pure functions; no baseline changed.

The JVM gates use `-Pripdpi.skipNativeBuild=true`. Native protocol tests and Android native compilation are separate evidence. Normal and RTL renders were inspected again after the dedicated QUIC attempt label correction.


## Android artifact and integration boundary

`:core:engine:buildRustNativeLibs --offline` passed for the local `arm64-v8a` ABI in 5m 16s. `:core:engine:verifyLibXrayArtifacts` failed because `native/xray/artifacts` does not exist. This pre-existing producer artifact is required for APK packaging. No APK or device acceptance is claimed; `adb devices` listed no connected device.

The worktree was rebased onto `33f1ffdc3`, preserving the parallel diagnostics wording and translation-registry changes. The translation export generator added exactly 33 HTTP/3 keys; its read-only check passed. Native source and configuration did not change in that upstream range. Remote CI and device acceptance remain separate from local test results.


The combined-tree run after rebase passed `staticAnalysis`, both explicit locale lint gates, detection tests, all 1,633 diagnostics tests and the 13 app tests. The task remains in review for remote CI, APK producer artifacts and connected-device acceptance.
