---
task_id: TRN-1794198826397434
change: repair-std05-profile-contracts
commit_sha: 33c46516e362197a567884c0f6056d8bd02686d3
local: required
local_evidence: The original validation passed. Upstream SOCKS/REALITY and STD-04 fixes are preserved after rebase; affected combined checks remain required.
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

## Combined gates

The combined test run reported **929 passed, 0 failed, 13 ignored**, with no filtered tests. The eight packages were ripdpi-tls-profiles, ripdpi-xhttp, ripdpi-diagnostics-tls, ripdpi-diagnostics-fat-header, ripdpi-monitor-engine, ripdpi-relay-core, ripdpi-vless and ripdpi-android-fetch-adapter. The ignored external/soak tests and the opt-in live-keylog path do not provide runtime evidence here.

All-target Clippy passed for the same packages with -D warnings and the three await-holding warning flags. Format then found one constructor layout in F1. After rustfmt, the owning profiles suite passed again: **70 passed, 0 failed, 0 ignored**. Profiles Clippy and workspace format also passed again. No behavior changed during this format correction.

The remaining gates passed:

```text
cargo deny --locked --manifest-path native/rust/Cargo.toml check advisories bans licenses sources
just ci-gates
python3 scripts/ci/check_architecture_health.py
cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1 --no-deps
./taskctl validate
```

Compiler-backed commands used the reserved build-gate lane, pinned Rust 1.98.1, a worktree-owned target, at most four Cargo/CMake jobs, no incremental outputs and no host debug information. Test debug assertions remained enabled. The first attempts at admission failed from lack of disk space before Cargo ran; only owned disposable audit targets were removed. Those failures are not test results.

The full ci-gates command passed. Its Linux-owned runtime-platform API snapshot was skipped by the canonical host policy on macOS; the other required API snapshots matched. Architecture health had no new or worsened indicators. No baseline or golden changed. The new rcgen edge is test-only, uses the existing locked workspace version and changes no package version.

Independent read-only review found no actionable issue in the six repairs. A second review checked F1 static metadata and callers. No unsafe or async code was added. A staged-index check confirmed that the six isolated patches reproduce the tested application source.

## Finding commits

| Finding | Commit | Subject |
|---|---|---|
| F1 | 72aa8273515d325a9f4b1ba812380b12d338e208 | fix(tls): report static validation without wire-size claims |
| F2 | 6d08aa44a8e66da38b2e2be742322baab9ad23ef | fix(tls): validate rotation without selecting a profile |
| F3 | c1c59a7afe18f785b55a8d95d2cc0dabb0af3d8c | fix(diagnostics): retain the first ClientHello plan |
| F4 | e8961f320fead84a0c3b09ea9a54af45caae76fc | fix(tls): validate the native default profile alias |
| F5 | ce432076048915f1b0c455346d9915ab87817f27 | docs(tls): separate preset snapshots from browser parity |
| F6 | dcfdcc9bbd8f99179b8e8b9c196cf89184caaf94 | docs(tls): clarify the TLS 1.3 cipher API limit |

## Evidence and limits

The local command journal is /tmp/std05-validation-state/all-results.json. Relevant logs are /tmp/std05-f1-red.log, /tmp/std05-f2-f4-observed-red.log, /tmp/std05-f3-observed-red.log, /tmp/std05-combined-tests.log, /tmp/std05-combined-clippy.log, /tmp/std05-profiles-after-format.log, /tmp/std05-deny.log and /tmp/std05-ci-gates.log. Each finding also has its own commit-hook log under /tmp/std05-F1-commit.log through /tmp/std05-F6-commit.log.

No Android build/device, public-server/DPI probe, root activation, real secret, packaged release or deployment was used. Browser impersonation, resumption, fragmented/HRR acceptance and external compatibility remain outside this repair. Host loopback results are local V, not E or full TLS conformance. Exact-source hosted CI remains required until its result is observed. The network registry and original audit report are unchanged.

## Rebase acceptance

The branch was rebased without conflicts onto origin/main at cd08ced31. Upstream assertion privacy, scoped SOCKS authentication and REALITY interoperability changes are preserved. The finding hashes above refer to the rebased commits. Post-rebase checks remain required before integration.

The STD-04 rebase uses origin/main at a1b8cd2aa7c2f3fd93d62994b77477f63061bd72. STD-04 already supplies the shared Satisfied status and a stronger real-capture regression. Conflict resolution preserves that implementation and test; F1 adds its 512-byte payload assertion and static-status field documentation. The duplicate F1 capture test was removed. F5 preserves the newer upstream KEM consumer boundaries and only changes misleading snapshot descriptions without changing assertion conditions. Combined validation remains required.

## STD-04 integration checks

The post-STD-04 run passed: **1580 passed, 0 failed, 15 ignored, 0 filtered** across the same eight packages plus ripdpi-tunnel-core and ripdpi-diagnostics-runner. All-target Clippy, format, just ci-gates, architecture health, locked metadata and task contracts passed. Independent conflict-resolution review found no issue. The journal is /tmp/std05-validation-state/rebased-fixed-results.json; logs use /tmp/std05-rebased-fixed-*.log.

An earlier run after the SOCKS authentication rebase had two failures in UDP fixtures. Both positive fixtures used the provider username control but expected native vpn08 scope. The reserved native-proxy username is ripdpi. Commit 33c46516e362197a567884c0f6056d8bd02686d3 changes those two test inputs only. Scope, password, datagram, source-port, owner and socket-reuse assertions are preserved. Production authentication and its negative tests are unchanged. Independent review and the full tunnel suite passed.

Upstream STD-06 has since changed certificate policy and removed an unused TLS verification argument. Its integration and affected checks remain required. The local front-matter status remains required for that reason.
