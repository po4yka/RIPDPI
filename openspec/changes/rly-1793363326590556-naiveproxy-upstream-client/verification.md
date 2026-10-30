---
task_id: RLY-1793363326590556
change: rly-1793363326590556-naiveproxy-upstream-client
commit_sha: 42ddc8aa5bf7f1b9c9a96ba0e06a33ddfb147faa
local: passed
local_evidence: At 8f5e7fd49 and 42ddc8aa5 - cargo fmt, workspace clippy -D warnings, cargo test -p ripdpi-naiveproxy -p ripdpi-vless, fuzz lock --locked; Gradle service/app unit suites, service lint, staticAnalysis (BUILD SUCCESSFUL); earlier full suites at 52e8da4b3; 76 acceptance pytest cases; manifest check; androidTest compile; arch-health --check; taskctl validate.
remote_ci: required
remote_ci_evidence: Dispatched CI run 41078666387 on dd7f4eaf8 (containing steps 1-7) passed; a run on the gap commits is pending.
device: passed
device_evidence: android-naiveproxy passed on leased RIPDPI_Pool API 37 arm64 emulator at 42ddc8aa5 (/private/tmp/claude-2042741446/-Users-npochaev-GitHub-RIPDPI/26bd3aec-6b8e-499c-80c2-4dfdd76ff68b/scratchpad/run-gaps-final); pin-free phase ran libripdpi-naive-chromium.so with config-path argv only and reached health healthy; wrong password stayed unknown; wrong pin degraded; transfers matched receipts; leak scan 0; upstream JA4 t13d1517h2_8daaf6152771_cb7bf5808d99 with no HelloRetryRequest.
artifact: passed
artifact_evidence: fetch-naive-upstream.py verified APK and libnaive.so sha256 for arm64-v8a, armeabi-v7a, x86 and x86_64 (idempotent rerun); the githubFull arm64 debug APK contains lib/arm64-v8a/libripdpi-naive-chromium.so (7226896 B, 3898423 B deflated). Release per-ABI size gate not run; v0.2.1 per-ABI APKs are 67-75 MiB against the 170 MiB gate.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RLY-1793363326590556-001 | RLY-1793363549742358 | NaiveUpstreamLauncherTest selection tests; pinned argv unchanged test | passed |
| REQ-RLY-1793363326590556-001 | RLY-1793363549742632 | run-final naive-processes.json: upstream for pin-free, Rust helper for pinned | passed |
| REQ-RLY-1793363326590556-002 | RLY-1793363549741947 | test_fetch_naive_upstream.py (digest mismatch, missing member, zip-slip) and real four-ABI fetch | passed |
| REQ-RLY-1793363326590556-002 | RLY-1793363549742084 | HelperExecutableJniLibsTaskTest missing/tampered cases; staged file in the debug APK | passed |
| REQ-RLY-1793363326590556-003 | RLY-1793363549742358 | argv, config content, 0600 mode and per-start deletion tests; run-final credential_in_argv false | passed |
| REQ-RLY-1793363326590556-004 | RLY-1793363549742358 | listen URI unit test (authenticated socks on 127.0.0.1) | passed |
| REQ-RLY-1793363326590556-004 | RLY-1793363549742632 | run-final wrong-password phase failed closed with no destination receipt | passed |
| REQ-RLY-1793363326590556-005 | RLY-1793363549742221 | resolve.rs unit tests and tests/resolve_cli.rs | passed |
| REQ-RLY-1793363326590556-005 | RLY-1793363549742358 | resolve client dns-class tests (non-zero exit, timeout, empty, garbage addresses) | passed |
| REQ-RLY-1793363326590556-006 | RLY-1793363549742358 | readiness and early-exit unit tests | passed |
| REQ-RLY-1793363326590556-006 | RLY-1793363549742632 | run-final: VPN reached Running; 1 MiB transfer sha256 matched receipts | passed |
| REQ-RLY-1793363326590556-007 | RLY-1793363549742769 | /private/tmp/claude-2042741446/-Users-npochaev-GitHub-RIPDPI/26bd3aec-6b8e-499c-80c2-4dfdd76ff68b/scratchpad/run-final/test-1/tls-summary.json: upstream connections JA4 t13d1517h2_8daaf6152771_cb7bf5808d99, one ClientHello, no HelloRetryRequest | passed |
| REQ-RLY-1793363326590556-005 | RLY-1793372750077466 | resolve-mode tests: DoH without protect on the public path, reachability ordering, single-address skip, 8 s deadline (cargo test -p ripdpi-naiveproxy -p ripdpi-vless, 86 + 144 passed) | passed |
| REQ-RLY-1793363326590556-006 | RLY-1793372750077677 | NaiveUpstreamLauncherTest: padding None and unannounced closed: OK give no evidence; proxy-side codes only; ERR_HTTP2_* kept; unrecognized format event; base64 and lowercase-hex redaction | passed |
| REQ-RLY-1793363326590556-006 | RLY-1793372750078099 | /private/tmp/claude-2042741446/-Users-npochaev-GitHub-RIPDPI/26bd3aec-6b8e-499c-80c2-4dfdd76ff68b/scratchpad/run-gaps-final: health healthy (pin-free), unknown (wrong password), degraded (wrong pin); leak-scan.json totals password 0, destination 0 | passed |
| REQ-RLY-1793363326590556-008 | RLY-1793372750077677 | NaiveUpstreamLauncherTest: padding None and unannounced closed: OK give no evidence; proxy-side codes only; ERR_HTTP2_* kept; unrecognized format event; base64 and lowercase-hex redaction | passed |
| REQ-RLY-1793363326590556-008 | RLY-1793372750078099 | /private/tmp/claude-2042741446/-Users-npochaev-GitHub-RIPDPI/26bd3aec-6b8e-499c-80c2-4dfdd76ff68b/scratchpad/run-gaps-final: health healthy (pin-free), unknown (wrong password), degraded (wrong pin); leak-scan.json totals password 0, destination 0 | passed |
| REQ-RLY-1793363326590556-009 | RLY-1793372750077888 | path-needs-pin notice in 10 locales; RelayFieldsTest; app lint and translatable-keys regen stable | passed |
