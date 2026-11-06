---
task_id: DNS-1793945565828575
change: kotlin-dns-library-codec
commit_sha: 159017362841ac9576e55db14b70652b7d992923
local: passed
local_evidence: All 7219 affected unit tests, full staticAnalysis, ci-gates, locked Cargo metadata and independent review passed. The final combined Gradle run passed after rebase onto 88116d19e.
remote_ci: required
remote_ci_evidence: Pending integrated main push and hosted checks.
device: not_applicable
device_evidence: No Android routing API or permission change; real local UDP and TCP fixtures verify transport. External-network and device acceptance are not claimed.
artifact: not_applicable
artifact_evidence: No release artifact is requested.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-KDNS-WIRE | DNS-1793945680585019 | diagnostics-data 87 tests; diagnostics 1658 tests; independent review | Passed |
| REQ-KDNS-TCP | DNS-1793945681167350 | DirectDnsTransportTest: real loopback UDP/TCP exchange, large datagram and question rejection | Passed |
| REQ-KDNS-PROBES | DNS-1793945681717226 | Service 2061 tests; app 3413 tests; diagnostics HTTPS/DoQ regressions | Passed |

## Local observations

- The pre-change reproduction accepted a query packet as a response. DirectDnsTest failed before the replacement.
- Independent review found and then verified fixes for high-bit TTL/OPT loss in dnsjava and false DoQ success on truncated messages.
- The first complete service run had one timeout in the unchanged PluggableTransportSocksBridgeWireTest. A complete rerun with two Gradle workers passed all 2061 service tests, and the next combined run passed again. No assertion or timeout was changed.
- Native dependencies built after removing the ambient CARGO_BUILD_JOBS override. The missing pinned NaiveProxy artifact was fetched with the repository script and SHA-256 verification.

## Final local commands

- `build-gate -- env -u CARGO_BUILD_JOBS ./gradlew :core:diagnostics-data:testDebugUnitTest :core:diagnostics:testDebugUnitTest :core:service:testDebugUnitTest :app:testGithubFullDebugUnitTest staticAnalysis -Pripdpi.skipNativeBuild=true --continue --max-workers=2 --console=plain`: exit 0. This is the repository CI mode for JVM tests; no device or release artifact claim is made.
- Unit reports: diagnostics-data 87, diagnostics 1658, service 2061, app 3413; zero failures, errors or skipped tests.
- `build-gate -- just ci-gates`: exit 0 after rebase. Architecture health has no new or worsened indicators; source contracts and API snapshots match.
- `cargo metadata --manifest-path native/rust/Cargo.toml --locked --no-deps --format-version 1`: exit 0.
- `git diff --check` and task validation passed. Independent review accepted both protocol corrections; final Detekt simplification preserves resolver outcomes.
