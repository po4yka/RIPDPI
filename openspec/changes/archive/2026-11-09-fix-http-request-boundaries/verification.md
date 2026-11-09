---
task_id: PRX-1794211693515272
change: fix-http-request-boundaries
commit_sha: 305afee89b4b966eb549efd11fa314ddba0bfe88
local: passed
local_evidence: All five repairs passed independent review, default tests, decoder acceptance, Clippy, and offline CI gates.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI is not an acceptance dependency. A push is authorized, but no hosted result is claimed.
device: not_applicable
device_evidence: Host tests verify parser bytes and stream ownership. Android and root transport acceptance are not claimed.
artifact: not_applicable
artifact_evidence: No Android binary or release artifact is produced.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Observed evidence | Result |
|---|---|---|---|
| REQ-HTTP-MUT-001 | PRX-1794211790672166 | Initial-head assembly, timeout/EOF/limit passthrough, runtime body/H2 tests, real SOCKS loopback, Lua flow, remembered host, TUN sequence/capacity/retransmission/wrap tests; h11 and hyper-h2 wire decode | Passed |
| REQ-HTTP-MUT-002 | PRX-1794211791494644 | Bundled Lua partial/complete head tests, including a split CRLF; independent h11 confirms X-Token remains a header and body stays DATA | Passed |
| REQ-HTTP-MUT-003 | PRX-1794211792270604 | Earliest LF/CRLF boundary tests in packets and strategy-http; h11 confirms no body mutation or framing change | Passed |
| REQ-HTTP-MUT-004 | PRX-1794211793025990 | Absolute-form and CONNECT authority, invalid/duplicate/folded Host, socket/TUN semantic metadata versus raw markers; real SOCKS Block(target)/Block(decoy) delivery tests | Passed |
| REQ-HTTP-MUT-005 | PRX-1794211793798887 | HTTP/1.0 and 1.1 GET/POST/TRACE method EOL tests; httparse accepts raw leading CRLF and h11 confirms unchanged body | Passed |

Combined gates and independent review: PRX-1794211794419926.

## Observed checks

The 12-package set is: ripdpi-packets, ripdpi-config, ripdpi-proxy-config,
ripdpi-desync, ripdpi-desync-runtime, ripdpi-runtime-policy,
ripdpi-strategy-http, ripdpi-strategy-lua, ripdpi-strategy-registry,
ripdpi-proxy-runtime-desync-adapter, ripdpi-proxy-runtime,
and ripdpi-tunnel-intercept.

- `bash scripts/ci/cargo-guarded.sh cargo nextest run --locked --manifest-path native/rust/Cargo.toml -p <each package above>`: exit 0; 1758 passed, 13 existing ignored tests skipped, 81 binaries, no retries. Log: `/tmp/std09-final-tests.log`.
- From `native/rust`, `bash ../../scripts/ci/cargo-guarded.sh cargo test --locked --doc -p <each package above>`: exit 0; all 12 targets, zero executable doc examples. Log: `/tmp/std09-final-doc-tests.log`.
- From `native/rust`, `bash ../../scripts/ci/cargo-guarded.sh cargo clippy --locked --all-targets --no-deps -p <each package above> -- -D warnings`: exit 0. Log: `/tmp/std09-final-clippy.log`.
- These commands used `CARGO_TARGET_DIR=/tmp/ripdpi-std09-combined-target CARGO_BUILD_JOBS=4`. Exact expanded commands and counts: `/tmp/std09-final-validation.txt`.
- `CARGO_TARGET_DIR=/tmp/std09-parser-target CARGO_BUILD_JOBS=4 just ci-gates`: exit 0. Architecture, budgets, contract and locale checks passed. API snapshots match; runtime-platform snapshot is Linux-owned and the existing gate skips it on Darwin. Log: `/tmp/std09-final-ci-gates.log`.
- `python3 scripts/ci/check_architecture_health.py`: exit 0. No baseline change. Log: `/tmp/std09-final-architecture.log`.
- `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1`: exit 0. Output: `/tmp/std09-final-cargo-metadata.json`.
- Temporary production-rlib probe plus h11 0.16.0, hyper-h2 4.3.0, and httparse 1.10.1: 20 acceptance checks passed. It captures real loopback TCP writes, verifies decoded bodies and trailing data, and checks authority and raw Host separately. Evidence: `/tmp/ripdpi-std09-repair-acceptance/result.json`, `captures/`, `rustc-command.json`, `httparse-provenance.json`. The httparse oracle was compiled directly from the versioned local crates.io source; no dependency or lockfile was changed.
- Independent read-only review covered each repair. It found an ambiguous folded Host and TCP sequence-wrap reopening. Both were reproduced and fixed before acceptance. The rebase review also found two retained TLS test calls that needed the new argument; they now test TLS with HTTP refinement disabled.

## Commit and input identity

The final rebase includes upstream native CI repairs and completed STD-10 MASQUE/XHTTP changes, including its vendored h3 and workspace lockfile update. These changes are preserved. All 12 HTTP repair package directories and bundled Lua assets are byte-identical between f38611609 and 305afee89; the whole native workspace is not identical. The same 12-package suite was run after integration from native/rust with RUSTUP_TOOLCHAIN=1.98.1: 1758 passed, 13 existing skips, no retries (`/tmp/std09-integration-tests.log`). Both rustc and cargo reported 1.98.1 (`/tmp/std09-integration-versions.log`). All-target Clippy exited 0 on that toolchain (`/tmp/std09-integration-clippy.log`); the selected packages had no reported warnings. The upstream vendor/h3 dependency emitted nine compiler warnings, so the whole graph is not warning-free. Offline CI gates passed again (`/tmp/std09-rebased-ci-gates.log`). Architecture and locked metadata passed again (`/tmp/std09-integrate-architecture.log`, `/tmp/std09-integrate-cargo-metadata.json`). Production rlibs and independent decoder acceptance were rebuilt from native/rust with the pinned compiler. An accidental build from the checkout root selected system Rust 1.97.0; it was interrupted with exit 130 and is not acceptance evidence. The prerequisite restoration is in origin/main as a43980af2; its duplicate job commit was skipped.

## Limits

C is implementation and caller evidence. V is the host execution above. S is the requirement comparison in the STD-09 audit, not a result of a passing test. E for an external peer remains unestablished.

- The 13 skips are six relay-interoperability, three soak, and four load tests. No new HTTP regression is ignored.
- Three existing wire_e2e tests return early when RIPDPI_RUN_WIRE_E2E is unset. Their reported PASS is not packet-capture evidence: udp_fake_burst_delivers_expected_datagram_count, udp_fake_burst_packets_have_quic_long_header, and udp_quic_chain_steps_emit_dummy_split_and_fake_version_packets.
- Linux-only capture and raw-socket tests are cfg-excluded on Darwin. No physical device, root activation, real secret, external server, or external HTTP intermediary was used.
- TUN unit tests verify classification and sequence ownership. They do not prove actual root packet emission, VPN lifecycle, or an APK build.
- HTTP mutation covers the first complete supported request head. Later keep-alive requests pass unchanged. Unsupported methods and HTTP/2 are opaque passthrough. Optional inspection passes incomplete or oversized heads unchanged and then disables HTTP mutation. Required hostname inspection keeps its explicit failure path. This repair does not add a general HTTP endpoint or a request-smuggling guarantee.
