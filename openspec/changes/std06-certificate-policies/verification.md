---
task_id: TRN-1794198667426286
change: std06-certificate-policies
commit_sha: 482be1a96ec8a0aade69e770db63276db60dc398
local: passed
local_evidence: 409 top-level tests passed: TLS profiles 71, WebTunnel 9, DoT 17, monitor verifier regression 1, and five diagnostic libraries 311. Two pre-existing fixture-timing tests remained ignored. Affected-package Clippy with all targets and denied warnings, formatting, cargo metadata --locked, and just ci-gates passed. Independent general and cancel-safety reviews had no actionable findings.
remote_ci: required
remote_ci_evidence: Hosted CI for the final pushed source has not been observed. No manual workflow was dispatched.
device: not_applicable
device_evidence: This change owns Rust verification policy and internal Rust signatures. Host loopback and subprocess regressions exercise the affected logic. No JNI or device-specific behavior changes.
artifact: not_applicable
artifact_evidence: No distributable artifact is owned by this source fix.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-STD06-SAN | TRN-1794198793896705 | cargo test --locked --jobs 4 -p ripdpi-tls-profiles: 71 passed, including TLS 1.2/1.3 SAN regression. | passed |
| REQ-STD06-ROOTS | TRN-1794198794706108 | trust_store integration test passed, including both subprocess environment sources, plain-Boring positive controls and explicit-root controls. | passed |
| REQ-STD06-DIAGNOSTICS | TRN-1794198795509654 | Monitor verifier regression passed. Five affected diagnostic library suites passed (311 tests, two pre-existing ignored tests). All affected targets compiled under Clippy with denied warnings. | passed |
| REQ-STD06-OVERRIDES | TRN-1794198793896705 | WebTunnel client_e2e: 9 passed; DNS resolver dot_ tests: 17 passed, including matching and wrong pins. | passed |

## Combined gates

TRN-1794198796214639 owns affected crate tests and clippy, cargo metadata --locked, architecture health, just ci-gates, and independent review. No result is claimed before its command completes.

## Review and fixture triage

Independent read-only review found no actionable issues in F1-F3. The cancel-safety review found no changed async functions or suspension points.

The first full diagnostics command exposed two pre-existing SNI-test transport mismatches: the client began TLS, but PlainFatHeaderServer expected raw HTTP. The owning baseline files matched source SHA 7b475fb9e byte-for-byte; a separate baseline runtime was not run because the shared build gate was occupied. The two fixtures now use the existing TlsHttpServer, with their assertions unchanged. Independent review accepted this test-only correction. The repeated five-library run with --test-threads=4 passed: 311 top-level tests, zero failures, two pre-existing ignored timing tests. No ignored test is counted as acceptance evidence.

All compiler commands use native/rust as cwd (Rust 1.98.1), --locked, at most four build jobs, and the local ripdpi build lane. Earlier wrong-toolchain or disk-full attempts are not validation evidence. Only owned disposable failed-build outputs were removed.

## Observed local commands

All Cargo commands ran from native/rust with Rust 1.98.1, CARGO_TARGET_DIR=/tmp/std06-audit/target and the build-gate ripdpi lane.

- `cargo test --locked --jobs 4 -p ripdpi-tls-profiles`: 71 passed; TLS 1.2/1.3 SAN and process-scoped roots tests passed.
- `cargo test --locked --jobs 4 -p ripdpi-webtunnel --test client_e2e`: 9 passed.
- `cargo test --locked --jobs 4 -p ripdpi-dns-resolver --lib dot_`: 17 passed.
- `cargo test --locked --jobs 4 -p ripdpi-monitor-engine --lib try_tls_handshake_uses_explicit_verifier_on_non_default_https_port`: 1 passed.
- `cargo test --locked --jobs 4 --lib -p ripdpi-diagnostics-tls -p ripdpi-diagnostics-fat-header -p ripdpi-diagnostics-http -p ripdpi-diagnostics-runner -p ripdpi-diagnostics-telegram -- --test-threads=4`: 23 + 78 + 126 + 26 + 58 passed. Child-process replay summaries are not counted twice.
- `cargo clippy --locked --jobs 4 --all-targets --no-deps -p ripdpi-tls-profiles -p ripdpi-diagnostics-tls -p ripdpi-diagnostics-fat-header -p ripdpi-diagnostics-http -p ripdpi-diagnostics-runner -p ripdpi-diagnostics-telegram -p ripdpi-monitor-engine -- -D warnings`: passed through cargo-guarded.sh under the held build gate.
- `cargo fmt --check`, `cargo metadata --locked --no-deps --format-version 1`, and `just ci-gates`: passed. Rust API snapshots matched; no baselines changed.

The two unchanged ignored tests are connectivity::trigger_fuzzing::tests::dns_probe_appends_dns_trigger_fuzzing_details and domain_probe_appends_http_trigger_fuzzing_details. Both already state that local fixture timing is flaky under full-workspace load. They are outside the certificate policy acceptance cases.

Android/device, external-provider compatibility, production deployment and manual hosted workflow runs were not performed.

## Implementation commits

- `482be1a96` test(diagnostics): use TLS fixtures for SNI probes
- `32f662c6e` fix(diagnostics): remove unused TLS verification flag
- `ee94779fb` fix(tls): restrict trust to bundled and explicit roots
- `0d167b53f` fix(tls): reject server identities without SAN
