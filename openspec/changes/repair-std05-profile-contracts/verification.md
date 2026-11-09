---
task_id: TRN-1794198826397434
change: repair-std05-profile-contracts
commit_sha: d16fbb4c62e41c58221c95c82be32794c5255a68
local: passed
local_evidence: Final rebased source passed 1582 tests with 0 failures and 15 ignored; Clippy, format, ci-gates, architecture health, locked metadata and task contracts passed. Independent review is clean.
remote_ci: required
remote_ci_evidence: Exact-source hosted CI has not been observed.
device: not_applicable
device_evidence: Host and loopback regressions cover these repairs. Android acceptance is not claimed.
artifact: not_applicable
artifact_evidence: No packaged or release artifact is owned by this repair.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-STD05-F1 | TRN-1794198931080001 | Actual Firefox/Safari records have 512-byte payloads and 517-byte records. Metadata now reports static Satisfied; legacy enum shape remains available. Regression failed before the fix and passed after it. | Passed |
| REQ-STD05-F2 | TRN-1794198932517327 | The original gate changed counter 0 to 10. The isolated integration test now keeps counter and seed unchanged, then observes the independently derived seed-zero selection. | Passed |
| REQ-STD05-F3 | TRN-1794198934501667 | The old caller lost the plan after a successful Auto handshake. Certificate-verified Auto/TLS1.2/TLS1.3 loopback handshakes now retain it. Boundary tests reject incomplete records and exclude later writes. | Passed |
| REQ-STD05-F4 | TRN-1794198935969699 | Original consumer validation rejected native_default. The fixed table accepts all six names and the alias and rejects unknown, short native aliases and metadata-only labels. | Passed |
| REQ-STD05-F5 | TRN-1794198937235252 | All original assertions and the configuration SHA-256 remain. Tests and docs identify internal snapshots and target labels without browser-version or external-acceptance claims. | Passed |
| REQ-STD05-F6 | TRN-1794198938191907 | Pinned C header/source and boring 5.3.0 were checked. Comments identify the available C API, absent safe Rust wrapper and hardware-dependent default. Wire policy is unchanged. | Passed |

## Final combined gates

The final run reported **1582 passed, 0 failed, 15 ignored, 0 filtered**. Packages: ripdpi-tls-profiles, ripdpi-xhttp, ripdpi-diagnostics-tls, ripdpi-diagnostics-fat-header, ripdpi-monitor-engine, ripdpi-relay-core, ripdpi-vless, ripdpi-android-fetch-adapter, ripdpi-tunnel-core and ripdpi-diagnostics-runner. The ignored external/soak tests and opt-in live-keylog path do not provide runtime evidence here.

All-target Clippy passed for the same packages with -D warnings and the three await-holding warning flags. Workspace format, just ci-gates, architecture health, locked Cargo metadata and task contracts passed. Cargo deny passed before rebase; subsequent upstream commits changed no package version or dependency edge. The exact command arguments and exit codes are in /tmp/std05-validation-state/std06-final-results.json. Logs use /tmp/std05-std06-final-*.log.

```text
# From native/rust/:
cargo test -p ripdpi-tls-profiles -p ripdpi-xhttp -p ripdpi-diagnostics-tls -p ripdpi-diagnostics-fat-header -p ripdpi-monitor-engine -p ripdpi-relay-core -p ripdpi-vless -p ripdpi-android-fetch-adapter -p ripdpi-tunnel-core -p ripdpi-diagnostics-runner --no-fail-fast --locked --jobs 4
bash ../../scripts/ci/cargo-guarded.sh cargo clippy -p ripdpi-tls-profiles -p ripdpi-xhttp -p ripdpi-diagnostics-tls -p ripdpi-diagnostics-fat-header -p ripdpi-monitor-engine -p ripdpi-relay-core -p ripdpi-vless -p ripdpi-android-fetch-adapter -p ripdpi-tunnel-core -p ripdpi-diagnostics-runner --all-targets --locked --jobs 4 -- -W clippy::await_holding_lock -W clippy::await_holding_refcell_ref -W clippy::await_holding_invalid_type -D warnings
cargo fmt --all --check
# From the checkout root:
just ci-gates
python3 scripts/ci/check_architecture_health.py
cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1 --no-deps
./taskctl validate
```

Compiler-backed commands used the reserved build-gate lane, pinned Rust 1.98.1, a worktree-owned target, at most four Cargo/CMake jobs, no incremental outputs and no host debug information. Test debug assertions remained enabled. Initial admission attempts failed from disk pressure before Cargo ran; only owned disposable audit targets were removed. Those failures are not test results.

The full ci-gates command passed. Its Linux-owned runtime-platform API snapshot was skipped by canonical host policy on macOS; the other required API snapshots matched. Architecture health had no new or worsened indicators. No baseline or golden changed. The rcgen edge is test-only and uses the existing locked workspace version without a package version change.

## Red/green and review evidence

Before the fixes, the F1 real-record metadata check failed, F2 validation changed counter 0 to 10, F4 rejected native_default, and F3 lost its plan after a successful Auto handshake. Those regressions passed after the repairs. The original combined run passed 929 tests with 13 ignored. After the STD-04 rebase and UDP fixture correction, the intermediate run passed 1580 tests with 15 ignored. The final counts above include STD-06 and IPC integration.

Independent read-only review found no actionable issue in the repairs, F1 semantics, the two UDP fixture inputs, STD-04 conflict resolution or the final STD-06 rebase. F3 adds checked synchronous record slicing; its real loopback tests retain certificate verification. No unsafe or async implementation was added. All commit hooks ran normally.

## Finding commits

| Finding | Commit | Subject |
|---|---|---|
| F1 | 8f5510f70f6ace7ba740b786ffd8b8fb08f06f12 | fix(tls): report static validation without wire-size claims |
| F2 | 8049e16a01581faaa71789cbea93860c0ceecd66 | fix(tls): validate rotation without selecting a profile |
| F3 | d1029d2c967d599acaef8b7c41c1cf42750f3d2a | fix(diagnostics): retain the first ClientHello plan |
| F4 | 6bf8547eaa06cbce7c4acf09a4e0dd713fd77974 | fix(tls): validate the native default profile alias |
| F5 | 2dcebc376a8aa2da35cd973047b6b507b0deb812 | docs(tls): separate preset snapshots from browser parity |
| F6 | 1407f1d0dad4d4adc29edc273c5607152641804c | docs(tls): clarify the TLS 1.3 cipher API limit |

## Upstream integration

The final tested base is cdf053fbe9761defbda70166953a4e88b977d0a9. Upstream assertion privacy, scoped SOCKS authentication, REALITY, STD-04, STD-06 and IPC fixes are preserved.

STD-04 already supplies the shared Satisfied status and a stronger real-capture metadata regression. F1 preserves that implementation and test, adds its 512-byte payload assertion and documents the static-status field. The duplicate capture test was removed. F5 preserves the newer upstream KEM consumer boundaries; all original static assertion conditions and the configuration hash remain.

STD-06 removed an unused TLS verification argument. The F3 test was adapted inside its finding commit by removing that argument only. Its real SAN localhost certificate, trusted root, verifier, TLS version, JA3 and first-flight assertions remain. Production trust and SAN checks are unchanged by this repair.

An earlier post-SOCKS-rebase run had two failures in UDP positive fixtures. They used provider username control but expected native vpn08 scope. The reserved native-proxy username is ripdpi. Separate integration commit d0fcfc20e6a03b31dd9ce541bb1b78f64b643e45 changes those two inputs only. Scope, password, datagram, source-port, owner and socket-reuse assertions are preserved. Production authentication and negative tests are unchanged; the full final tunnel suite passes.

## Evidence and limits

Earlier command journals are /tmp/std05-validation-state/all-results.json, rebased-results.json and rebased-fixed-results.json in the same directory. Relevant red logs are /tmp/std05-f1-red.log, /tmp/std05-f2-f4-observed-red.log and /tmp/std05-f3-observed-red.log. The final journal and logs named above supersede earlier results for the current source.

No Android build/device, public-server/DPI probe, root activation, real secret, packaged release or deployment was used. Browser impersonation, resumption, fragmented/HRR acceptance and external compatibility remain outside this repair. Host loopback results are local V, not E or full TLS conformance. Exact-source hosted CI remains required until observed. The network registry and original audit report are unchanged.
