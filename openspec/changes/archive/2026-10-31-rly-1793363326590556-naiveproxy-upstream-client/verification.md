---
task_id: RLY-1793363326590556
change: rly-1793363326590556-naiveproxy-upstream-client
commit_sha: 32a9b2c8aa0a52945cb9f6c5e38c43f8194a30c3
local: passed
local_evidence: At 8f5e7fd49 and 42ddc8aa5 - cargo fmt, workspace clippy -D warnings, cargo test -p ripdpi-naiveproxy -p ripdpi-vless, fuzz lock --locked; Gradle service/app unit suites, service lint, staticAnalysis (BUILD SUCCESSFUL); earlier full suites at 52e8da4b3; 76 acceptance pytest cases; manifest check; androidTest compile; arch-health --check; taskctl validate.
remote_ci: passed
remote_ci_evidence: Final main 32a9b2c8aa0a52945cb9f6c5e38c43f8194a30c3, which contains the NaiveProxy upstream client (84b73e779) and the NaiveUriCodec detekt fix (7aa9318bf), passed full push CI run 41170842447: ci-required succeeded with 47 jobs passed and 18 route skips, including rust-lint (workspace clippy), rust-workspace-tests, kotlin-coverage (Kotlin unit suites), gradle-static-analysis (staticAnalysis including lint and detekt), architecture-health and six release-verification variants. Its github-full per-ABI release APK size gate passed: armeabi-v7a 71.8, arm64-v8a 76.7, x86 78.8 and x86_64 80.0 MiB against the 170 MiB budget. Earlier: dispatched run 41078666387 on dd7f4eaf8 passed.
device: passed
device_evidence: android-naiveproxy passed on leased RIPDPI_Pool API 37 arm64 emulator at 83246fb52 (content of 84b73e779 before rebase; /private/tmp/claude-2042741446/-Users-npochaev-GitHub-RIPDPI/26bd3aec-6b8e-499c-80c2-4dfdd76ff68b/scratchpad/run-improve): pinned, pin-free and HTTP/3 transfers matched receipts with an HTTP/3 CONNECT at the server; health healthy after the pin-free transfer, degraded after the wrong pin and after the wrong password settled; leak scan 0 password and 0 destination hits. Earlier pass at 42ddc8aa5 (run-gaps-final).
artifact: passed
artifact_evidence: fetch-naive-upstream.py verified both digests for all four ABIs. CI run 41098434082 check_abi_apk_size_budget on the githubFull release APK: armeabi-v7a 71.8, arm64-v8a 76.6, x86 78.8, x86_64 80.0 MiB upper bounds against the 170 MiB budget. The release-candidate gate itself has not run.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-RLY-1793363326590556-001 | RLY-1793363549742358 | NaiveUpstreamLauncherTest selection tests; pinned argv unchanged test; run-final naive-processes.json: upstream for pin-free, Rust helper for pinned | passed |
| REQ-RLY-1793363326590556-002 | RLY-1793363549741947 | test_fetch_naive_upstream.py (digest mismatch, missing member, zip-slip) and real four-ABI fetch; HelperExecutableJniLibsTaskTest missing/tampered cases; staged file in the debug APK | passed |
| REQ-RLY-1793363326590556-003 | RLY-1793363549742358 | argv, config content, 0600 mode and per-start deletion tests; run-final credential_in_argv false | passed |
| REQ-RLY-1793363326590556-004 | RLY-1793363549742358 | listen URI unit test (authenticated socks on 127.0.0.1); run-final wrong-password phase failed closed with no destination receipt | passed |
| REQ-RLY-1793363326590556-005 | RLY-1793363549742221 | resolve.rs unit tests and tests/resolve_cli.rs; resolve client dns-class tests (non-zero exit, timeout, empty, garbage addresses); resolve-mode tests: DoH without protect on the public path, reachability ordering, single-address skip, 8 s deadline (cargo test -p ripdpi-naiveproxy -p ripdpi-vless, 86 + 144 passed) | passed |
| REQ-RLY-1793363326590556-006 | RLY-1793363549742358 | readiness and early-exit unit tests; run-final: VPN reached Running; 1 MiB transfer sha256 matched receipts; NaiveUpstreamLauncherTest: padding None and unannounced closed: OK give no evidence; proxy-side codes only; ERR_HTTP2_* kept; unrecognized format event; base64 and lowercase-hex redaction; /private/tmp/claude-2042741446/-Users-npochaev-GitHub-RIPDPI/26bd3aec-6b8e-499c-80c2-4dfdd76ff68b/scratchpad/run-gaps-final: health healthy (pin-free), unknown (wrong password), degraded (wrong pin); leak-scan.json totals password 0, destination 0 | passed |
| REQ-RLY-1793363326590556-007 | RLY-1793363549742769 | /private/tmp/claude-2042741446/-Users-npochaev-GitHub-RIPDPI/26bd3aec-6b8e-499c-80c2-4dfdd76ff68b/scratchpad/run-final/test-1/tls-summary.json: upstream connections JA4 t13d1517h2_8daaf6152771_cb7bf5808d99, one ClientHello, no HelloRetryRequest | passed |
| REQ-RLY-1793363326590556-008 | RLY-1793372750077677 | NaiveUpstreamLauncherTest: padding None and unannounced closed: OK give no evidence; proxy-side codes only; ERR_HTTP2_* kept; unrecognized format event; base64 and lowercase-hex redaction; /private/tmp/claude-2042741446/-Users-npochaev-GitHub-RIPDPI/26bd3aec-6b8e-499c-80c2-4dfdd76ff68b/scratchpad/run-gaps-final: health healthy (pin-free), unknown (wrong password), degraded (wrong pin); leak-scan.json totals password 0, destination 0 | passed |
| REQ-RLY-1793363326590556-009 | RLY-1793372750077888 | path-needs-pin notice in 10 locales; RelayFieldsTest; app lint and translatable-keys regen stable | passed |
| REQ-RLY-1793363326590556-010 | RLY-1793386616416891 | NaiveUpstreamOutputParserTest auth case with the recorded ERR_PROXY_AUTH_UNSUPPORTED line; emulator run-improve wrong-password phase settled to degraded | passed |
| REQ-RLY-1793363326590556-011 | RLY-1793386616908842 | check-mode Rust test (407 auth, 502 ok, closed port connect); lab Caddy run ok/auth/connect; NaiveProxyProfileCheckerTest; ConfigNaiveProfileCheckRunnerTest | passed |
| REQ-RLY-1793363326590556-012 | RLY-1793386617391528 | ProxyUriCodecTest quic/concurrency/pin cases; NaiveUpstreamConfigTest; pinned refusal preflight test; emulator HTTP/3 phase transfer with an HTTP/3 CONNECT at the server | passed |
| REQ-RLY-1793363326590556-013 | RLY-1793386615947971 | tls.rs ClientHello tests; lab capture rustpq: one ClientHello, ServerHello selects X25519MLKEM768, JA4 t13d0911h2_f91f431d341e_6d05706c5ed3 | passed |

## Final hosted gates

Final main 32a9b2c8aa0a52945cb9f6c5e38c43f8194a30c3, which contains the NaiveProxy upstream client (84b73e779) and the NaiveUriCodec detekt fix (7aa9318bf), passed full push CI run 41170842447: ci-required succeeded with 47 jobs passed and 18 route skips, including rust-lint (workspace clippy), rust-workspace-tests, kotlin-coverage (Kotlin unit suites), gradle-static-analysis (staticAnalysis including lint and detekt), architecture-health and six release-verification variants. Its github-full per-ABI release APK size gate passed: armeabi-v7a 71.8, arm64-v8a 76.7, x86 78.8 and x86_64 80.0 MiB against the 170 MiB budget. Earlier: dispatched run 41078666387 on dd7f4eaf8 passed.
