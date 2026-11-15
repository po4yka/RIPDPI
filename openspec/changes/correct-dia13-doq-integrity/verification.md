---
task_id: DGN-1794744781459086
change: correct-dia13-doq-integrity
commit_sha: 2d0a425bdf544e8f19da7fdca2e325f6db5f7d5b
local: passed
local_evidence: "49 Rust tests, 137 JVM tests, Android lint, Clippy and just ci-gates passed in the combined checkout."
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: null
artifact: passed
artifact_evidence: "The Android support API snapshot was regenerated and checked. The existing JNI facade signatures passed the host test."
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

Task ID: `DGN-1794744781459086`

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DGN-1794744781459086-001 | DGN-1794744875673581 | JVM fixtures retain NODATA, NXDOMAIN, SERVFAIL and REFUSED and give an inconclusive verdict without a usable baseline. | passed |
| REQ-DGN-1794744781459086-002 | DGN-1794744876643244 | JVM tests check resolver profile identity, exact agreement, disagreement, weak overlap and mixed profiles. App tests check visible comparison sources. | passed |
| REQ-DGN-1794744781459086-003 | DGN-1794744878459295 | JVM tests cover divergent, timed-out, missing and disabled checks. App tests check the warning and observed versus planned counts. | passed |
| REQ-DGN-1794744781459086-004 | DGN-1794744879977143 | Loopback and JVM tests cover typed failures, wrong TLS names and ALPN, DNS errors with an empty Question, TC and extended RCODE. All locale keys pass Android lint. | passed |
| REQ-DGN-1794744781459086-005 | DGN-1794744881108511 | Loopback tests use a silent first address and a successful second address within the diagnostic deadline. The shared DNS resolver retry remains covered. | passed |
| REQ-DGN-1794744781459086-006 | DGN-1794744882037175 | JNI and resolver tests cover malformed lengths and Base64. Kotlin tests cover 12, 65535 and 65536 byte payload boundaries. Wire tests cover DNS ID and FIN. | passed |
| REQ-DGN-1794744781459086-007 | DGN-1794744883219405 | Native tests cover cancellation during an actual UDP exchange, bounded permits and a late completion. JVM tests cover cancel and release, blocking lookup, two concurrent runs and the silent preflight. | passed |

## Local checks

- Rust 49 tests passed. Counts by crate were 37 in the resolver, 9 in the platform adapter, 2 in the handle registry and 1 in the JNI facade. Cargo used `--locked --offline --jobs 3`. The external Cloudflare fixture was excluded.
- JVM 137 tests passed. The diagnostics-data, diagnostics and app tasks ran 10, 105 and 22 tests. The first integration run found a stale factory argument in a new test. The fix and the repeat run are included in the commit SHA above.
- `:app:lintGithubFullDebug :core:service:lintDebug`, owning Rust Clippy with `-D warnings`, Rust format, `just ci-gates` and strict task/OpenSpec validation passed. The source gates checked the API snapshot, language parity and translation export. The Linux-owned API snapshot follows its existing host gate.
- Independent reviewers checked the comparison policy, cancellation, JNI lifecycle and Rust resolver. The review fixes are included in the test results above.

## Scope of evidence

The host and loopback tests cover the diagnostic code and its local wire behavior. Android device and external provider acceptance are pending. Android socket protection still calls the platform synchronously. Cancelling or reaching the deadline discards any late result. The process-wide limit remains in effect until the active native exchange returns.
