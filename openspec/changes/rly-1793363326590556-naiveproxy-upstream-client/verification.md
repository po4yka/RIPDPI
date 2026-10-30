---
task_id: RLY-1793363326590556
change: rly-1793363326590556-naiveproxy-upstream-client
commit_sha: 33c407fe02d793b1078877bbe55603e921bbd94d
local: required
local_evidence: At 52e8da4b3 (code) and 33c407fe0 (tests): cargo fmt, workspace clippy -D warnings, cargo test -p ripdpi-naiveproxy; Gradle data/runtime-state/service/engine-api/engine/app unit suites, app and service lint, staticAnalysis (BUILD SUCCESSFUL); build-logic convention tests; 81 acceptance/fetch pytest cases; manifest check; androidTest compile; actionlint on all workflows; arch-health --check; taskctl validate.
remote_ci: required
remote_ci_evidence: null
device: required
device_evidence: android-naiveproxy on leased RIPDPI_Pool_1 (API 37 arm64 google_apis emulator) at 33c407fe0, status passed, cleanup passed (/private/tmp/claude-2042741446/-Users-npochaev-GitHub-RIPDPI/26bd3aec-6b8e-499c-80c2-4dfdd76ff68b/scratchpad/run-final). Pin-free phases ran libripdpi-naive-chromium.so with argv = config path only (credential_in_argv false); 1 MiB transfers matched destination receipts; wrong password and wrong pin failed closed; pinned phases ran the Rust helper unchanged.
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
| REQ-RLY-1793363326590556-005 | RLY-1793372750077466 | resolve-mode ordering and no-protect DoH tests | pending |
| REQ-RLY-1793363326590556-006 | RLY-1793372750077677 | upstream-output parser health tests | pending |
| REQ-RLY-1793363326590556-006 | RLY-1793372750078099 | emulator health assertions | pending |
| REQ-RLY-1793363326590556-008 | RLY-1793372750077677 | parser sanitization and base64 redaction tests | pending |
| REQ-RLY-1793363326590556-008 | RLY-1793372750078099 | emulator logcat and export scan | pending |
| REQ-RLY-1793363326590556-009 | RLY-1793372750077888 | editor notice in 10 locales, lint | pending |
