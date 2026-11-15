---
task_id: DGN-1794732423381679
change: repair-dia06-ech-probe-contracts
commit_sha: d61e468158df81612ee68f8c83e39e415771e2ec
local: passed
local_evidence: "Rebased combined source: 164 tests passed, 3 existing external tests ignored; all-target Clippy with three await-holding lints, exact just ci-gates, architecture, locked metadata, task contracts and format check passed."
remote_ci: not_applicable
remote_ci_evidence: "Acceptance uses the required local gates for this dormant library. Automatic hosted checks after the authorized push remain separate evidence."
device: not_applicable
device_evidence: "No Android, JNI or production scheduling path changes; device acceptance is not claimed."
artifact: not_applicable
artifact_evidence: "No packaged native artifact or release is owned by this change."
deployment: not_applicable
deployment_evidence: "No deployment or external endpoint probe is owned by this change."
---

# Verification

Source commit: `d61e468158df81612ee68f8c83e39e415771e2ec`, based on origin/main `95543ef95fcc47f211bd0d000da64fa8c6d224b9`. The rebase brought 42 commits after the first implementation base; the complete combined gates ran after that rebase.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DIA06-NODATA | DGN-1794732651298755 | F1, `406f2ffa96f1a7e12014a11f5e3baf4779ef4dfe`; owning regression and independent reviews | passed |
| REQ-DIA06-CONTEXT | DGN-1794732662908652 | F2, `fd4b7e0d2eddcb4d973239fe94d56de3a73baa52`; owning regression and independent reviews | passed |
| REQ-DIA06-REJECTION | DGN-1794732681565638 | F3, `6c21670402304d7d729995c9996a61126a63452a`; owning regression and independent reviews | passed |
| REQ-DIA06-ADMISSION | DGN-1794732702904779 | F4, `54aca8b0c2ec60b715a39c181dc2510e4966215d`; owning regression and independent reviews | passed |
| REQ-DIA06-UNCERTAINTY | DGN-1794732709699208 | F5, `d61e468158df81612ee68f8c83e39e415771e2ec`; owning regression and independent reviews | passed |
| REQ-DIA06-DEADLINE | DGN-1794732712536071 | F6, `ff45062d9da709ce2aa008682691b21e5125b488`; owning regression and independent reviews | passed |

Review and combined-gate step: `DGN-1794732715671338`.

## Observed behavior

- F1: real UDP NOERROR/NODATA returns no config. NXDOMAIN and SERVFAIL remain errors. Four initial DNS tests passed.
- F2: unsupported resolver, relay or strategy context stops before I/O. A panic-on-use driver proves that neither network phase starts. Network-scope metadata remains allowed. Eight dispatcher tests passed.
- F3: BoringSSL confirms ECH rejection without retry configs; the typed result preserves rejection. Valid key rotation still succeeds with one new connection to the same peer. Seven initial TLS tests passed.
- F4: AliasMode and incompatible bindings cannot supply ECHConfig. Mandatory keys, automatic HTTPS keys, default port 443 and normalized original target are checked. Unsupported-only results are inconclusive, not missing publication. A later compatible record can be selected. Nineteen DNS tests passed.
- F5: a silent TLS peer returns Inconclusive and observes EOF at the deadline. Typed certificate and TLS errors remain definite failures. Eleven TLS tests passed, including cancellation, wrong public name and untrusted certificate cases.
- F6: the complete DNS lookup shares one checked deadline. The RED run took 757.96125 ms for a 250 ms budget with 250 ms scheduler tolerance. The fixed suite passed 22 tests, including zero/MAX durations and no retry after expiry. ResolveHosts::Never removes synchronous hosts-file reads from HTTPS setup.

The final rebased suite passed **164 tests: 115 library and 49 integration tests**. Three existing public-network tests remained ignored. No test was newly ignored or removed to pass.

## Commands and retained results

The Cargo working directory is `native/rust` in the dedicated integration worktree. Heavy work ran under one `build-gate --lane ripdpi` lease, with a private target directory, four Cargo/CMake jobs and shared sccache.

```sh
cargo test --locked --offline --jobs 4 -p ripdpi-diagnostics-probes
cargo clippy --locked --offline --jobs 4 -p ripdpi-diagnostics-probes --all-targets --no-deps -- -D warnings -D clippy::await_holding_lock -D clippy::await_holding_refcell_ref -D clippy::await_holding_invalid_type
cargo fmt --check --package ripdpi-diagnostics-probes
just ci-gates
python3 scripts/ci/check_architecture_health.py
cargo metadata --manifest-path native/rust/Cargo.toml --locked --offline --format-version 1
./taskctl validate
```

The format check ran separately from `native/rust`. Metadata ran from the repository root. Every command above passed. The exact source gates reported 18 existing architecture indicators, 0 new, 0 worsened and 0 stale entries; runtime contracts covered 5 surfaces and 28 gates; diagnostics boundary violations were 0. API snapshots matched. The Linux-owned ripdpi-runtime-platform snapshot was skipped on Darwin as the repository script specifies. The route report showed existing informational coverage of 31/51 routes.

Dependency review also passed `cargo deny --config deny.toml --locked check advisories bans licenses sources` from native/rust with cargo-deny 0.20.2. All four categories were OK; no default-policy fallback occurred. Deny inputs and Cargo package identities did not change in the rebase. Only two existing test-dependency edges, boring and rcgen, were added; package versions did not change.

General and async reviewers independently checked every fix before commit. The async review found two corrections: the generic dispatcher now states its conditional backend cancellation contract, and the DNS setup disables an irrelevant synchronous hosts read. The parent checked both exact corrections; tests and strict Clippy passed afterward. Integration resolved only overlapping test-module declarations and kept both modules.

Retained evidence is under `/Users/npochaev/Downloads/RIPDPI_network_audits/`:

- `DIA-06-recovery-root/logs/combined-validation-state.json` records the frozen source SHA, commands, exit codes and timings. Adjacent logs contain tests, Clippy, source gates, architecture and metadata results.
- `DIA-06-recovery-context/logs/result.json` and commit-hook logs record F2 acceptance.
- `DIA-06-recovery-dns/validation-state.json` and `commit-state.json` record F1/F4/F6 RED/GREEN, exact intermediate trees, the hosts correction and hooks.
- `DIA-06-recovery-tls/batch-state.json` and `precommit-state.json` record F3/F5 RED/GREEN, dependency policy and hooks.

## Failed attempts and recovery

Initial TLS fixture failures were not counted as defect reproduction. The fixture first closed the connection early; Darwin also inherited nonblocking mode on accepted sockets. The fixture now holds the stream until alert/EOF and sets blocking mode explicitly. The corrected RED reached typed authenticated ECH rejection before the fix.

An external removal deleted the first temporary integration worktree during this task. The main checkout and shared Git objects stayed intact. All authored files were backed up and recovered byte-for-byte into worktrees under GitHub/RIPDPI-worktrees. Queued validations stopped during this recovery were not counted as passes. The final combined run used the recovered, rebased source and its own target directory. No unrelated worktree or branch was deleted.

An integration cherry-pick triggered an extra hook before the private target/lane environment was set. Its compiler output is not used as acceptance evidence. The full combined validation above ran again with the correct private target and reserved lane.

## Scope and remaining evidence

The generic runner remains a library API, outside production monitor scheduling. No UI, JNI, wire, storage, golden fixture or quality baseline was changed. New Rust enum variants are internal API breaks and both relevant commits include BREAKING CHANGE footers.

TCP/TLS cancellation is observed. The initial hostname connect still uses Tokio spawn_blocking(getaddrinfo); drop does not stop an OS DNS job that has already started. These tests do not establish full system-DNS cancellation. Full alias/endpoint selection, Android/VPN, external ECH servers, packaged ABIs and deployment are outside this repair.

C records current source and review. V records the named local runs. S remains limited to the selected requirements in the DIA-06 audit. External deployment compatibility E is not established by local tests or a Git push.
