---
task_id: TRN-1794158908788387
change: repair-audited-tls-client-contracts
commit_sha: f68b1a6534ff3d38feb05892fc63f3c97088faf8
local: passed
local_evidence: All seven regressions, ten affected packages after rebase, Clippy, format, ci-gates, architecture and locked metadata passed. Independent general and cancellation reviews passed. See the commands and limits below.
remote_ci: required
remote_ci_evidence: No exact-source hosted CI run has been observed. Local success does not establish hosted CI.
device: not_applicable
device_evidence: Host and loopback checks cover this repair. Android device acceptance remains unverified.
artifact: not_applicable
artifact_evidence: No release or packaged Android artifact is published by this repair.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

This repair addresses the seven confirmed findings in the STD-01 audit. It does not establish full TLS conformance or external interoperability. The audit report and network registry are unchanged.

## Requirement evidence

| Requirement | Execution step | Observed evidence | Result |
|---|---|---|---|
| REQ-STD01-F01 | TRN-1794159075134654 | An explicit TLS probe on a nonstandard port emits a ClientHello and rejects a plaintext peer. Existing whitelist success tests now use a real TLS server. | Passed |
| REQ-STD01-F02 | TRN-1794159076761797 | A CA-trusted DoT peer with the wrong pin is rejected before DNS payload. Pinned mode always uses its configured pin-only verifier. Default unpinned PKIX checks remain active. | Passed |
| REQ-STD01-F03 | TRN-1794159077470340 | Default and custom DoT builders offer dot; an incompatible selected ALPN is rejected before DNS bytes; a legacy peer without ALPN still works. | Passed |
| REQ-STD01-F04 | TRN-1794159077989449 | MASQUE H2 TCP and UDP reject http/1.1 and absent ALPN before application bytes against real rustls peers. xHTTP TLS and REALITY require h2; positive TLS/REALITY carrier fixtures still pass. | Passed |
| REQ-STD01-F05 | TRN-1794159078853808 | Timeout and task-abort QUIC tests observe request-cancelled code 3, preserve a concurrent query, and reuse the connection for another query. | Passed |
| REQ-STD01-F06 | TRN-1794159079416629 | Silent and slow-drip TLS peers stop at scan/cell deadlines; cancellation closes workers. Administrative interruptions produce Skipped, not block signals. A post-join drain preserves terminal outcomes at the receive boundary. | Passed |
| REQ-STD01-F07 | TRN-1794159079966114 | Public TUIC, direct relay and chain builders reject zero_rtt=true with Unsupported before DNS/network. Normal zero_rtt=false and loopback paths pass. | Passed |

F06 bounds the TLS handshake after the existing protected TCP connect. It does not make kernel TCP connect or the protection callback instantly cancellable. The prior bounded TCP connect remains in place. F07 deliberately rejects unsupported early data; it does not implement replay-safe 0-RTT. Existing saved/imported profiles with zero_rtt=true now receive an explicit error.

## Regression reproduction

Regressions were added in isolated worktrees and run against unchanged production snapshots. Every Cargo resolver invocation used --locked. RED means a behavioral assertion failed, not a compiler/setup failure.

- F01: the baseline sent plaintext HE instead of the TLS record prefix 0x16 0x03 to the loopback observer.
- F02/F03/F05: baseline DNS tests: 167 passed, 5 failed. Failures covered trusted wrong pin, dot ALPN, incompatible ALPN, timeout isolation and dropped-query cancellation. The snapshot initially lacked three tracked BoringSSL build sources; restoring those exact baseline sources fixed setup before this behavioral RED run.
- F04: both MASQUE TCP/UDP ALPN negative tests failed on the baseline because H2 bytes were sent without negotiated h2.
- F06: the unchanged monitor binary ran the slow-drip regression and failed after 2.04 seconds rather than stopping at the short scan deadline.
- F07: the baseline public TUIC test and two relay builder tests failed to return Unsupported before network work.

Baseline commands:

```text
cargo test --manifest-path native/rust/Cargo.toml -p ripdpi-diagnostics-fat-header -p ripdpi-monitor-engine --locked std01_ -- --nocapture
/tmp/std01-monitor-red-bin std01_ --nocapture
cargo test -p ripdpi-dns-resolver --lib --locked -- --nocapture
cargo test -p ripdpi-masque --lib --locked alpn -- --nocapture
cargo test -p ripdpi-tuic -p ripdpi-relay-core --lib --no-fail-fast --locked zero_rtt -- --nocapture
```

The immutable monitor binary SHA-256 was 62efb8972ceeea939585ab410db65abe7755a5b679ffdc7e70ea871068a07bf3. RED snapshots and logs reside under /tmp/std01-*. They are local evidence, not committed fixtures or external compatibility evidence.

## Local validation before upstream integration

Run from native/rust with four build/test workers, CMAKE_BUILD_PARALLEL_LEVEL=4 and RIPDPI_RUN_NETWORK_TESTS=0. Heavy checks and commit hooks used build-gate --lane ripdpi. No hooks or quality gates were skipped.

```text
cargo test -p ripdpi-dns-resolver -p ripdpi-diagnostics-tls -p ripdpi-diagnostics-fat-header -p ripdpi-monitor-engine -p ripdpi-masque -p ripdpi-xhttp -p ripdpi-vless -p ripdpi-tuic -p ripdpi-relay-core --no-fail-fast --locked
cargo clippy -p ripdpi-dns-resolver -p ripdpi-diagnostics-tls -p ripdpi-diagnostics-fat-header -p ripdpi-monitor-engine -p ripdpi-masque -p ripdpi-xhttp -p ripdpi-vless -p ripdpi-tuic -p ripdpi-relay-core --locked --all-targets --keep-going -- -W clippy::await_holding_lock -W clippy::await_holding_refcell_ref -W clippy::await_holding_invalid_type -D warnings
cargo test -p ripdpi-dns-resolver -p ripdpi-masque -p ripdpi-relay-core --no-fail-fast --locked
cargo fmt --all --check
```

The full combined test run reported 1160 passed, 0 failed, 13 ignored, with no filtered tests. After test-only lint fixes, the three affected packages passed again: 509 passed, 0 failed, 3 ignored. The live keylog test returns early unless explicitly enabled; its formal PASS does not prove live keylog behavior. Ignored external interoperability and soak tests were not run. Earlier combined failures exposed plaintext whitelist fixtures and an ALPN-less xHTTP fixture; both were corrected to real protocol peers before the passing run. Clippy findings were fixed without suppressions or baseline changes.

From the repository root, these checks passed:

```text
just ci-gates
python3 scripts/ci/check_architecture_health.py
cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1 --no-deps
./taskctl validate
```

Independent read-only reviewers covered the production diff and new tests. The general review found a receive/join boundary race in F06; the author fixed it and the reviewer accepted the correction. The cancellation review found no remaining production bug: 20 changed async functions, 14 cancel-safe and 6 explicitly NOT cancel-safe test helpers, zero missing contracts. Test-runtime teardown limits are stated in those helpers.

## Separate repair commits

| Finding | Commit | Subject |
|---|---|---|
| F01 | 21382ade792a2777a48ff55acae1aeca27313749 | fix(diagnostics): preserve TLS on nonstandard ports |
| F02 | a1467f816a80aa6d8e063ed2a459098e865cedc8 | fix(dns): enforce configured DoT pins |
| F03 | 663afeaf9c83cfa008275995885590fe82423720 | fix(dns): negotiate the DoT application protocol |
| F04 | 7ae6bbb8e1b6aee2a48b17235ae625e44278d5dd | fix(tls): require h2 before starting HTTP/2 transports |
| F05 | b0afa7fcf8512ef5db46d581487dbe315d752853 | fix(dns): isolate DoQ query cancellation |
| F06 | be623041ef7aa23733d49fe22c22707cee2a3705 | fix(diagnostics): bound TLS worker deadlines and cancellation |
| F07 | f68b1a6534ff3d38feb05892fc63f3c97088faf8 | fix(tuic): reject unsupported early data |

## Remaining evidence limits

- Hosted CI for the final pushed source is still required until its result is observed.
- No Android or physical device, public server, root activation, real secret, release artifact or deployment was used.
- External TLS 1.2/1.3 compatibility, live upstream peers and live keylog output were not retested. Local loopback evidence is V for these regressions, not E.
- No dependency, Cargo.lock, compiled native artifact, locale set, baseline, network registry or audit report was changed.

## Upstream integration and final local validation

The branch was rebased onto origin/main at 1d9c9a31848476a1989ca0804e545659c7d4602b. Upstream STD-02 commits 6eeeeb075 and a7e67abb8 already implement F03/F04. Their stronger production code is retained. Our F03 commit adds a Firefox custom-builder profile and the full TLS 1.2/1.3, direct/SOCKS, default/custom legacy-ALPN matrix. Our F04 commit adds the TCP dialing entrypoint to the existing TCP-over-transport and UDP negative matrix. The upstream identity checks and dedicated REALITY H2 API remain intact. F06 preserves name normalization before TCP connect and uses the normalized name for the cancellable handshake.

After rebase, the full ten-package run reported 1239 passed, 0 failed and 13 ignored, with no filtered tests. It includes ripdpi-tls-profiles plus the nine packages above. The live-keylog opt-in and ignored-test limitations still apply. The final all-targets Clippy run uses the same ten packages and the same warning flags. Format, ci-gates, architecture and locked metadata also passed on the rebased source.

Final commands, run through the same reserved build lane:

```text
cargo test -p ripdpi-tls-profiles -p ripdpi-dns-resolver -p ripdpi-diagnostics-tls -p ripdpi-diagnostics-fat-header -p ripdpi-monitor-engine -p ripdpi-masque -p ripdpi-xhttp -p ripdpi-vless -p ripdpi-tuic -p ripdpi-relay-core --no-fail-fast --locked
cargo clippy -p ripdpi-tls-profiles -p ripdpi-dns-resolver -p ripdpi-diagnostics-tls -p ripdpi-diagnostics-fat-header -p ripdpi-monitor-engine -p ripdpi-masque -p ripdpi-xhttp -p ripdpi-vless -p ripdpi-tuic -p ripdpi-relay-core --locked --all-targets --keep-going -- -W clippy::await_holding_lock -W clippy::await_holding_refcell_ref -W clippy::await_holding_invalid_type -D warnings
cargo fmt --all --check
just ci-gates
python3 scripts/ci/check_architecture_health.py
cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1 --no-deps
```

Independent reviewers rechecked origin/main..HEAD after conflict resolution. No production ALPN helper or REALITY API was replaced by the older local version. Logs: /tmp/std01-run-rebased-*.log; execution journal: /tmp/std01-run-rebased-results.jsonl.
