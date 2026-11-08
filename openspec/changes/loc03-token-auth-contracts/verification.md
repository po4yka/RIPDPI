---
task_id: PRX-1794114937480022
change: loc03-token-auth-contracts
commit_sha: 5e5fe76d34a89fd30e8360d01f7af1e8c0356148
local: passed
local_evidence: 707 affected Rust library tests passed; all-target Clippy with await lints, formatting, locked metadata and just ci-gates passed. Independent correctness and async cancellation reviews approved every fix.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI is not an acceptance gate for these host-reproduced native regressions; no hosted result is claimed.
device: not_applicable
device_evidence: No Android lifecycle or platform interface change; real host loopback tests cover the changed boundaries. Android acceptance was not run.
artifact: not_applicable
artifact_evidence: No release package or native artifact is published by this change.
deployment: not_applicable
deployment_evidence: No production deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-LOC03-TOKEN | PRX-1794115012554155 | Listener regression: invalid 0/256-byte tokens fail before bind; actual authentication succeeds for 1/254/255 bytes in Mixed and BytePrefixed. HTTP-only behavior preserved. | passed |
| REQ-LOC03-REPLY | PRX-1794115013054978 | Live loopback test verifies malformed response versions 00/02/05/FF stop CONNECT and ASSOCIATE before any command bytes. | passed |
| REQ-LOC03-FIELDS | PRX-1794115013653338 | YAML parse and native shared Config::validate reject empty/oversized fields; helper sends zero bytes for invalid fields. Wire tests preserve 255-byte, multibyte, space and NUL credentials. | passed |
| REQ-LOC03-CORRELATION | PRX-1794115014313960 | Real sockets retain the literal attempt ID over IPv4, native IPv6 and mapped IPv4; wrong tokens fail and invalid UTF-8 does not become an ID. | passed |
| REQ-LOC03-PRESERVE | PRX-1794115014827819 | 707 library tests, all-target Clippy with await lints, fmt, locked metadata, ci-gates and independent reviews passed on the exact source commit above. | passed |

## Atomic fixes

| Finding | Integration commit | Behavior |
|---|---|---|
| F1 | 7bfe24967a4314c05559f1ae12b516dea8a581a7 | Validate listener token byte bounds before bind. |
| F2 | 50441042bfb4b03da9ce56a289e07e287cff1623 | Validate TUN RFC 1929 reply version. |
| F3 | 5e5fe76d34a89fd30e8360d01f7af1e8c0356148 | Validate credential bounds in configuration and before socket I/O. |
| F4 | de577049c005eed41b7c114a4f72c9dd65a0a5e5 | Canonicalize the peer IP for loopback correlation. |

## Commands and observed results

Commands ran from native/rust in the integration worktree, with pinned Rust 1.98.1. Heavy commands ran through build-gate --lane ripdpi, jobs at most four, a worktree-local target and sccache.

- `cargo test --locked --jobs 4 -p ripdpi-proxy-runtime -p ripdpi-tunnel-core -p ripdpi-tunnel-config --lib -- --test-threads=1`: proxy-runtime 316, tunnel-config 35 and tunnel-core 356 passed. Total 707; zero failed, ignored or filtered.
- `bash ../../scripts/ci/cargo-guarded.sh cargo clippy --locked --jobs 4 -p ripdpi-proxy-runtime -p ripdpi-tunnel-core -p ripdpi-tunnel-config --all-targets --no-deps -- -D warnings -D clippy::await_holding_lock -D clippy::await_holding_refcell_ref -D clippy::await_holding_invalid_type`: passed.
- `cargo fmt --check`: passed.
- `cargo metadata --locked --format-version 1 --no-deps`: passed.
- `just ci-gates` from the repository root: passed all source gates, including architecture health, file/hotspot budgets, contracts, protocol fixture pins, locale/translation parity and Rust API snapshots.
- The API snapshot script explicitly skips ripdpi-runtime-platform on Darwin because its canonical snapshot is owned by Linux. That Linux snapshot was not checked here; the required host gate still returned zero.
- Independent pr-reviewer approved F1-F4. The async cancellation specialist approved F2 and F3; no new unsafe code or suspension points were added.
- Each fix was committed with enabled repository hooks. No dependencies, lockfiles, API snapshots, schemas, locales, golden fixtures or baselines changed.

## Regression evidence and limits

Before each fix, the new tests reproduced the defect: invalid token reached bind; malformed auth reply allowed a command; invalid credentials passed configuration or emitted a greeting; mapped loopback lost its ID. The same behavior tests passed after the corresponding fix. Tests use independent literal wire bytes and real loopback sockets.

Local logs are under /tmp/ripdpi-loc03-fix-logs: f1-red/green/commit, f2-red/green/commit, f3-helper-red, f3-config-red, f3-green/commit, f4-red/green/commit, combined-tests, combined-clippy, combined-fmt, metadata.json and ci-gates. Temporary logs are supplementary; checked-in regression tests and the exact source commit above retain the verification inputs.

The original audit remains a historical record of its base commit. Timing recovery, raw Debug logging, account authentication, local TLS, GSSAPI and additional relay support were not confirmed defects in this scope and were not added. Physical devices, external servers, release artifacts, deployment and Android acceptance were not run or claimed.
