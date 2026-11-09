---
task_id: TRN-1794198826397434
change: repair-std05-profile-contracts
commit_sha: 7c45e6f76346c2eb5a0093924ffc16c3dd3f0592
local: passed
local_evidence: Regressions reproduced the original failures. Eight-package tests, all-target Clippy, format, dependency checks, ci-gates, architecture and locked metadata passed. Independent review found no actionable issue.
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
| F1 | 5282aa6ebde67e6862735949d48fe2f30567342c | fix(tls): report static validation without wire-size claims |
| F2 | 32d8110c14b9a75ed90c98f53c9ecd08110817eb | fix(tls): validate rotation without selecting a profile |
| F3 | e36aeb877e53f5bd9e235fe372c879711dab5406 | fix(diagnostics): retain the first ClientHello plan |
| F4 | 5c71f641296d03e92c2640636ad555763e788ddb | fix(tls): validate the native default profile alias |
| F5 | 64269ba791a52ac341fc295e2b045b09717b65e2 | docs(tls): separate preset snapshots from browser parity |
| F6 | 7c45e6f76346c2eb5a0093924ffc16c3dd3f0592 | docs(tls): clarify the TLS 1.3 cipher API limit |

## Evidence and limits

The local command journal is /tmp/std05-validation-state/all-results.json. Relevant logs are /tmp/std05-f1-red.log, /tmp/std05-f2-f4-observed-red.log, /tmp/std05-f3-observed-red.log, /tmp/std05-combined-tests.log, /tmp/std05-combined-clippy.log, /tmp/std05-profiles-after-format.log, /tmp/std05-deny.log and /tmp/std05-ci-gates.log. Each finding also has its own commit-hook log under /tmp/std05-F1-commit.log through /tmp/std05-F6-commit.log.

No Android build/device, public-server/DPI probe, root activation, real secret, packaged release or deployment was used. Browser impersonation, resumption, fragmented/HRR acceptance and external compatibility remain outside this repair. Host loopback results are local V, not E or full TLS conformance. Exact-source hosted CI remains required until its result is observed. The network registry and original audit report are unchanged.
