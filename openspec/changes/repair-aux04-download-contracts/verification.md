---
task_id: SVC-1794755640074922
change: repair-aux04-download-contracts
commit_sha: ced04ed40abe8f4d2324e157f27d116fc0a0f52a
local: passed
local_evidence: "40 Rust and 32 JVM tests, strict Clippy, ci-gates, full staticAnalysis and independent review passed. Exact logs are in the persistent AUX-04 evidence directory."
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
| REQ-AUX04-F1 | SVC-1794755720003749 | CatalogDownloadBoundsTest: 12 real loopback cases across three callers, HTTP 200/500, and declared/chunked bodies. Assertions require the byte-limit error, HTTP status, received request, and bounded socket reads. Existing geo, host and priors tests also pass. | passed |
| REQ-AUX04-F2 | SVC-1794755720588786 | Five real TLS tests cover clean and abrupt closure for close-delimited, exact/truncated Content-Length, and complete/truncated chunked responses (10 cases). TLS verification stays on with a local test root. | passed |
| REQ-AUX04-F3 | SVC-1794755721277092 | Four native lifecycle tests and three Kotlin cancellation tests cover pre-cancel, stalled body cancellation, peer socket closure, handle release, unknown handles and independent calls. JSON commands retain the existing JNI export. | passed |
| REQ-AUX04-F4 | SVC-1794755722049880 | Real native HTTP fixture checks the final Content-Type; Kotlin checks exact propagation and legacy absent-field handling. Default client/browser tests check Windows-1251, UTF-8 and binary output. | passed |

## Local commands and results

All compile commands use build-gate --lane ripdpi. Rust uses the pinned 1.100.0 toolchain from native/rust, four jobs, a private persistent target, debug information off, and incremental compilation off.

- cargo test --locked -p ripdpi-android-fetch-adapter -- --test-threads=1: 40 passed, zero failed, ignored or filtered.
- cargo clippy --locked -p ripdpi-android-fetch-adapter --all-targets -- -D warnings -D clippy::await_holding_lock -D clippy::await_holding_refcell_ref: passed.
- Owning Gradle engine and service tests: 32 passed, zero failed or skipped. See recovered-results.json for exact task filters. Native packaging is disabled for these JVM checks.
- just ci-gates: passed, including architecture, file and function budgets, contracts, locales and 13 Rust API snapshots. No baseline changes.
- ./gradlew staticAnalysis --max-workers=2 -Pripdpi.skipNativeBuild=true -Pripdpi.nativeCpuBudget=2 --no-daemon --no-configuration-cache --continue: passed, 827 tasks.
- Strict OpenSpec and task contracts: passed.
- Independent pr-reviewer, JNI and async reviews: no unresolved findings after corrections. Recovered-tree review found no actionable P1-P3 issues. Final structured autoreview found no actionable P0 issue; that threshold is not a full security claim.

Exact command ledgers, logs, JUnit counts, source hashes, patches and review output are in /Users/npochaev/.local/share/ripdpi-aux04-repair-evidence/. The original temporary worktrees disappeared before source commits. Recovery used the saved Git index and durable review patches. The recovered implementation was reviewed and all listed gates were run again in the persistent worktree.

## Evidence limits

C: the repaired source and callers were reviewed. V: the local host and loopback runs above were observed. S: the audit checked the applicable HTTP/TLS requirements, including RFC 9112 section 9.8; this is not full RFC conformance. E: no external-server interoperability is established.

No physical Android/CheckJNI run, real VPN protect acceptance, release binary, root activation or external-server probe was run. Those need a separately authorized device or external scope. Full JNI execution on Android remains an acceptance gap; host Kotlin and native tests prove the two sides separately.

The audit's URL diagnostic sinks, concurrent asset publication and DoH family-latency hypotheses remain unconfirmed. They are not included as extra fixes. Host/geo data trust and signed-priors verification keep their existing contracts. No dependency, baseline, golden fixture, key, registry or release artifact changed.

## Finding commits

- F3: ebd0234f15304291b7e5f0f9aacebb1247d6773f — fix(fetch): cancel owned HTTP requests through native handles
- F1: 8504775f719c8139df8d54659e403d08c9dbe16f — fix(catalog): bound responses before Retrofit buffering
- F2: add8a72b0273a3aba06b79dceb938dd3ac3baf1f — fix(fetch): reject incomplete TLS-delimited responses
- F4: ced04ed40abe8f4d2324e157f27d116fc0a0f52a — fix(browser): preserve native response content type
