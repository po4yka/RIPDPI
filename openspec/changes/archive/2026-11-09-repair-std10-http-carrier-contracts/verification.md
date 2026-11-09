---
task_id: TRN-1794211839090466
change: repair-std10-http-carrier-contracts
commit_sha: 98ea4879bdd90ec2ab751f25448edce5e73c7431
local: passed
local_evidence: Rebased local tests passed (421 selected unique tests), workspace clippy with async lints passed, just ci-gates passed, cargo deny passed, and independent source reviews passed.
remote_ci: not_applicable
remote_ci_evidence: No hosted CI run is claimed; this change owns local source validation.
device: not_applicable
device_evidence: No Android service or device route change; no device acceptance is claimed.
artifact: not_applicable
artifact_evidence: No release or APK delivery is owned by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-STD10-CANCEL | TRN-1794211922684114 | xHTTP sibling cancellation, bidirectional echo and third-open regression; 83 crate tests | passed |
| REQ-STD10-SETTINGS | TRN-1794211923197752 | H2 permission/cancel tests and six raw H3 peer tests (delay, missing/zero/invalid flags, GOAWAY, QUIC capability and cancellation) | passed |
| REQ-STD10-SUCCESS | TRN-1794211923197752 | Raw H2 201 TCP/UDP byte exchange; 3 MiB transfer, window updates, half-close, blocked-write reset and cancelled-open cleanup | passed |
| REQ-STD10-CAPSULE | TRN-1794211923739038 | Bounded parser tests; H2 malformed/truncated FIN protocol resets; H3 malformed/65528/65536/truncated FIN resets without later payload | passed |
| REQ-STD10-HEADERS | TRN-1794211923739038 | Forbidden-field unit matrix and independent raw H3 CL0/Content-Type rejection before UDP; optional Capsule-Protocol positive control | passed |
| REQ-STD10-DOCS | TRN-1794211922684114 | Documentation compared with Rust/Chromium dispatch, ALPN checks and profile checker | passed |

## Finding commits

| Finding | Commit | Result |
|---|---|---|
| F1 | 490a4fcd3 | Keep active xHTTP streams after another opening is cancelled. |
| F2 | 41df1a47b | Process peer SETTINGS before Extended CONNECT. |
| F3 | 9d4fdee53 | Require HTTP and QUIC Datagram support and reject invalid boolean settings. |
| F4 | 2713f9b60 | Carry valid non-200 H2 CONNECT success responses. |
| F5 | 86c2771c7 | Abort malformed, oversized or incomplete capsule streams. |
| F6 | 98ea4879b | Reject forbidden capsule response fields before user payload. |
| F7 | 33e6fa753 | Describe the actual Naive Rust/Chromium carrier selection. |

## Observed commands

Run from native/rust unless a repository-root command is named. The final commands ran on HEAD 97a334f6c45069ccb623d1328428ba981ade7193, based on origin/main ba8b6e1de098c9073601c2140601affb600f338b, plus the four-file F5 correction then committed as 4fd9e7d36. That correction uses H3_MESSAGE_ERROR for malformed capsules and incomplete FIN. It was folded into the F5 commit. `git diff --exit-code 4fd9e7d36 98ea4879bdd90ec2ab751f25448edce5e73c7431 -- native/rust` passed: the final native inputs are identical to the tested inputs.

- `cargo test --locked -p ripdpi-masque -p ripdpi-xhttp -p ripdpi-hysteria2 -p ripdpi-diagnostics-http --lib -- --test-threads=1`: 132 + 83 + 116 + 78 = 409 unique tests passed. Four diagnostics subprocess results are included in its 78 tests, not counted twice.
- `cargo test -p ripdpi-relay-core --locked masque -- --test-threads=1`: 12 passed, 211 filtered unit tests; integration binary selected zero tests (one filtered). Total selected unique tests: 421, zero failures.
- `bash ../../scripts/ci/cargo-guarded.sh cargo clippy --locked --workspace --all-targets -- -D warnings -D clippy::await_holding_lock -D clippy::await_holding_refcell_ref -D clippy::await_holding_invalid_type`: passed after rebase.
- `just ci-gates` at the repository root: passed after rebase. Architecture, LoC, hotspots, contracts, protocol fixture versions, diagnostics boundaries, routes, locales and translation export passed. API snapshots matched; the Linux-owned runtime-platform snapshot is skipped on macOS by the existing gate.
- `cargo deny --locked check`: advisories, bans, licenses and sources passed. Dependency inputs did not change during the rebase. Existing duplicate/yanked warnings and upstream h3 compiler warnings remain visible.
- `taskctl validate`: passed. Independent pr-reviewer and async-cancel-safety reviews reported no remaining source defect after the corrected H2 SETTINGS wait and the added flow-control/reset/cleanup tests.

Local logs: /tmp/std10-final-tests.log, /tmp/std10-final-relay-masque.log, /tmp/std10-final-workspace-clippy.log, /tmp/std10-final-ci-gates.log and /tmp/std10-deny.log. Logs are disposable; this record preserves the observed result and commands.

## Scope and limits

The earlier source-gate attempt was stopped before rebase; it is not recorded as a pass. The preliminary H2 gate using sender.ready() failed seven positive fixtures. The final gate polls the connection until peer SETTINGS change its initial zero stream limit, then checks permission; the positive suite passed.

The final F5 review resolved an error-code conflict: RFC 9297 Section 3.3 and RFC 9114 Section 4.1.2 require H3_MESSAGE_ERROR for malformed or incomplete capsules. The broad H3_DATAGRAM_ERROR registry description does not override that requirement. Tests assert the independent wire value 0x010e. Generic HTTP Datagram error-code mappings remain unchanged. Sources: [RFC 9297 Section 3.3](https://www.rfc-editor.org/rfc/rfc9297.html#section-3.3), [RFC 9114 Section 4.1.2](https://www.rfc-editor.org/rfc/rfc9114.html#section-4.1.2).

The h3 version remains 0.0.8. The vendored source preserves its license and upstream receipt; only peer-settings access and boolean validation change. The existing h2 0.4.20 package is used directly. No package version was added or bumped; Cargo also normalized two existing transitive lock edges while resolving the new direct edge.

Miri was not run for the native TLS/socket protocol suites. The new atomic selects only a reset code and publishes no associated data; its ordering was reviewed, and tests ran on ARM64. No external-provider, physical-device, root, secret, production, release or deployment action was used. These local checks do not establish external compatibility. H3 TCP MASQUE and capsule-only H3 outbound remain explicitly unsupported.
