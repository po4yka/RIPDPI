---
task_id: SVC-1794755640074922
change: repair-aux04-download-contracts
commit_sha: f7177d2c21c03d719014bb484d35d19fc8039e33
local: blocked
local_evidence: "AUX-04 regressions pass (40 Rust, 33 JVM), as do Clippy and ci-gates. Final staticAnalysis fails on seven inherited PCAP/VPN Detekt issues; see the integration section and exact log."
remote_ci: required
remote_ci_evidence: "Publication and exact-commit hosted CI remain pending."
device: not_applicable
device_evidence: "Local loopback and host tests are in scope. No physical or external acceptance is authorized."
artifact: not_applicable
artifact_evidence: "No release binary or golden fixture is produced."
deployment: not_applicable
deployment_evidence: "Git integration and publication only. No service deployment."
---

# Verification

Portfolio: SVC-1794755640074922. Audit: /Users/npochaev/Downloads/RIPDPI_network_audits/AUX-04.md.

## Requirement evidence

| Requirement | Step | Observed evidence | Result |
|---|---|---|---|
| REQ-AUX04-F1 | SVC-1794755720003749 | CatalogDownloadBoundsTest: 12 real loopback cases across three callers, HTTP 200/500, and declared/chunked bodies. Assertions require a byte-limit error, HTTP status, received request and bounded body reads. Shared-priors HTTP errors instead require the preserved immediate HTTP rejection and zero body bytes. Existing geo, host and priors tests also pass. | passed |
| REQ-AUX04-F2 | SVC-1794755720588786 | Five real TLS tests cover clean and abrupt closure for close-delimited, exact/truncated Content-Length, and complete/truncated chunked responses (10 cases). TLS verification stays on with a local test root. | passed |
| REQ-AUX04-F3 | SVC-1794755721277092 | Four native lifecycle tests and three Kotlin cancellation tests cover pre-cancel, stalled body cancellation, peer socket closure, handle release, unknown handles and independent calls. JSON commands retain the existing JNI export. | passed |
| REQ-AUX04-F4 | SVC-1794755722049880 | Real native HTTP fixture checks the final Content-Type; Kotlin checks exact propagation and legacy absent-field handling. Default client/browser tests check Windows-1251, UTF-8 and binary output. | passed |

## Local commands and results

All compile commands use build-gate --lane ripdpi. Rust uses the pinned 1.100.0 toolchain from native/rust, four jobs, a private persistent target, debug information off, and incremental compilation off.

- cargo test --locked -p ripdpi-android-fetch-adapter -- --test-threads=1: 40 passed, zero failed, ignored or filtered.
- cargo clippy --locked -p ripdpi-android-fetch-adapter --all-targets -- -D warnings -D clippy::await_holding_lock -D clippy::await_holding_refcell_ref: passed.
- Owning Gradle engine and service tests after integration: 33 passed, zero failed or skipped. See integrated-results.json for exact task filters. Native packaging is disabled for these JVM checks.
- just ci-gates: passed, including architecture, file and function budgets, contracts, locales and 13 Rust API snapshots. No baseline changes.
- ./gradlew staticAnalysis --max-workers=2 -Pripdpi.skipNativeBuild=true -Pripdpi.nativeCpuBudget=2 --no-daemon --no-configuration-cache --continue: passed, 827 tasks before integration. The integrated result is recorded below.
- Strict OpenSpec and task contracts: passed.
- Independent pr-reviewer, JNI and async reviews: no unresolved findings after corrections. Recovered-tree review found no actionable P1-P3 issues. Final structured autoreview found no actionable P0 issue; that threshold is not a full security claim.

Exact command ledgers, logs, JUnit counts, source hashes, patches and review output are in /Users/npochaev/.local/share/ripdpi-aux04-repair-evidence/. The original temporary worktrees disappeared before source commits. Recovery used the saved Git index and durable review patches. The recovered implementation was reviewed and all listed gates were run again in the persistent worktree.

## Evidence limits

C: the repaired source and callers were reviewed. V: the local host and loopback runs above were observed. S: the audit checked the applicable HTTP/TLS requirements, including RFC 9112 section 9.8; this is not full RFC conformance. E: no external-server interoperability is established.

No physical Android/CheckJNI run, real VPN protect acceptance, release binary, root activation or external-server probe was run. Those need a separately authorized device or external scope. Full JNI execution on Android remains an acceptance gap; host Kotlin and native tests prove the two sides separately.

The audit's URL diagnostic sinks, concurrent asset publication and DoH family-latency hypotheses remain unconfirmed. They are not included as extra fixes. Host/geo data trust and signed-priors verification keep their existing contracts. No dependency, baseline, golden fixture, key, registry or release artifact changed.

## Integration result

The implementation was rebased on 2d5806e6d0, then on 46cf8f41af. The second range contains only task/OpenSpec documents; all compiled inputs stayed unchanged. The AUX-05 shared-priors changes are preserved: HEAD bypasses body handling; non-2xx is cancelled and closed before any body read; successful responses keep cancelOnEarlyClose and now also use the common decoded-body cap. Independent review found no actionable merge defect. The incoming SharedPriors regression adds known-length/chunked/gzip success/error coverage for both endpoints (12 cases).

Integrated Rust 40/40, strict Clippy, JVM 33/33, just ci-gates, locked cargo metadata (113 workspace members), architecture health and structured autoreview passed. Exact commands and exit codes are in integrated-results.json; JUnit counts are in integrated-junit-summary.json. Source hashes are in integrated-source-hashes.json.

The full integrated staticAnalysis was run and failed with seven inherited Detekt issues in files unchanged by AUX-04:

| Source | Rule |
|---|---|
| app/src/main/kotlin/com/poyka/ripdpi/ui/screens/diagnostics/PcapCaptureContent.kt:51 | LoopWithTooManyJumpStatements |
| core/pcap-export/src/main/kotlin/com/poyka/ripdpi/pcap/PcapExportStaging.kt:61 | NestedBlockDepth |
| core/pcap-export/src/main/kotlin/com/poyka/ripdpi/pcap/PcapDocumentExport.kt:48 | TooGenericExceptionCaught |
| core/pcap-export/src/main/kotlin/com/poyka/ripdpi/pcap/PcapDocumentExport.kt:68 | TooGenericExceptionCaught |
| core/pcap-export/src/main/kotlin/com/poyka/ripdpi/pcap/PcapDocumentExport.kt:53 | InstanceOfCheckForException |
| core/pcap-export/src/main/kotlin/com/poyka/ripdpi/pcap/PcapCaptureRuntimeController.kt:315 | ReturnCount |
| core/service/src/main/kotlin/com/poyka/ripdpi/services/VpnTunnelRuntime.kt:58 | LargeClass |

The exact log is integrated-static.log (exit 1, 827 tasks). These five files and the Detekt configuration match the incoming main tree. No suppressions or baseline changes were made. Full local verification is blocked by those inherited issues, so the portfolio is not closed. The earlier full staticAnalysis pass on the repair base is historical evidence, not a pass for integrated main.

## Finding commits

- F1: f01bb194dfeebd9bd0540e769f623a6220de5538 — fix(catalog): bound responses before Retrofit buffering
- F2: 83d0ad7cfef154e7d50dc5a47a69e45cf79f21d5 — fix(fetch): reject incomplete TLS-delimited responses
- F3: 8a7345e72ddbb3ee8a3b1ff19648f2b54c824e47 — fix(fetch): cancel owned HTTP requests through native handles
- F4: f7177d2c21c03d719014bb484d35d19fc8039e33 — fix(browser): preserve native response content type
