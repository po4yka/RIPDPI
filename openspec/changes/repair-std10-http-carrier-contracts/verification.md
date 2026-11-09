---
task_id: TRN-1794211839090466
change: repair-std10-http-carrier-contracts
commit_sha: 33c7a34fb255eb9d9f39cac2fc03318b71b5621e
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
| F1 | e6366507d | Keep active xHTTP streams after another opening is cancelled. |
| F2 | bcd4c4ead | Process peer SETTINGS before Extended CONNECT. |
| F3 | cd439ac08 | Require HTTP and QUIC Datagram support and reject invalid boolean settings. |
| F4 | 3c81ada40 | Carry valid non-200 H2 CONNECT success responses. |
| F5 | 0b274cac4 | Abort malformed, oversized or incomplete capsule streams. |
| F6 | 33c7a34fb | Reject forbidden capsule response fields before user payload. |
| F7 | a7d3222d6 | Describe the actual Naive Rust/Chromium carrier selection. |

## Observed commands

Run from native/rust unless a repository-root command is named. All post-rebase commands used code commit 33c7a34fb255eb9d9f39cac2fc03318b71b5621e, based on origin/main 7bd65bae644a250a6d00c4207426be1fdffa8607.

- `cargo test --locked -p ripdpi-masque -p ripdpi-xhttp -p ripdpi-hysteria2 -p ripdpi-diagnostics-http --lib -- --test-threads=1`: 132 + 83 + 116 + 78 = 409 unique tests passed. Four diagnostics subprocess results are included in its 78 tests, not counted twice.
- `cargo test -p ripdpi-relay-core --locked masque -- --test-threads=1`: 12 passed, 211 filtered unit tests; integration binary selected zero tests (one filtered). Total selected unique tests: 421, zero failures.
- `cargo clippy --locked --workspace --all-targets -- -D warnings -D clippy::await_holding_lock -D clippy::await_holding_refcell_ref -D clippy::await_holding_invalid_type`: passed after rebase.
- `just ci-gates` at the repository root: passed after rebase. Architecture, LoC, hotspots, contracts, protocol fixture versions, diagnostics boundaries, routes, locales and translation export passed. API snapshots matched; the Linux-owned runtime-platform snapshot is skipped on macOS by the existing gate.
- `cargo deny --locked check`: advisories, bans, licenses and sources passed. Dependency inputs did not change during the rebase. Existing duplicate/yanked warnings and upstream h3 compiler warnings remain visible.
- `taskctl validate`: passed. Independent pr-reviewer and async-cancel-safety reviews reported no remaining source defect after the corrected H2 SETTINGS wait and the added flow-control/reset/cleanup tests.

Local logs: /tmp/std10-rebased-tests.log, /tmp/std10-rebased-relay-masque.log, /tmp/std10-rebased-workspace-clippy.log, /tmp/std10-rebased-ci-gates.log and /tmp/std10-deny.log. Logs are disposable; this record preserves the observed result and commands.

## Scope and limits

The earlier source-gate attempt was stopped before rebase; it is not recorded as a pass. The preliminary H2 gate using sender.ready() failed seven positive fixtures. The final gate polls the connection until peer SETTINGS change its initial zero stream limit, then checks permission; the positive suite passed.

The h3 version remains 0.0.8. The vendored source preserves its license and upstream receipt; only peer-settings access and boolean validation change. The existing h2 0.4.20 package is used directly. No package version was added or bumped; Cargo also normalized two existing transitive lock edges while resolving the new direct edge.

Miri was not run for the native TLS/socket protocol suites. The new atomic selects only a reset code and publishes no associated data; its ordering was reviewed, and tests ran on ARM64. No external-provider, physical-device, root, secret, production, release or deployment action was used. These local checks do not establish external compatibility. H3 TCP MASQUE and capsule-only H3 outbound remain explicitly unsupported.
