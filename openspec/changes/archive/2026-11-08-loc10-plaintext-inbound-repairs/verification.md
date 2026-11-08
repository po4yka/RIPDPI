---
task_id: PRX-1794127994095690
change: loc10-plaintext-inbound-repairs
commit_sha: 76ee6aa6b7bd00f6b3e06fb552e782d616b914ed
local: passed
local_evidence: Six affected crate suites passed 1154 test executions; six local interoperability tests passed separately; scoped clippy, formatting, ci-gates, locked metadata, task validation and independent review passed.
remote_ci: not_applicable
remote_ci_evidence: No hosted workflow was requested or run; local source and native gates cover the selected scope. A push is not evidence of hosted CI success.
device: not_applicable
device_evidence: No Android API, JNI, service lifecycle or UI change. Host loopback and Android command-line conversion tests cover the changed paths; no APK or device acceptance was run.
artifact: passed
artifact_evidence: Actual shipped CLI binary integration tests passed for plaintext input in BytePrefixed and Mixed modes and rejected LAN startup. The Android command-line JSON conversion tests passed.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-PLAIN-POLICY | PRX-1794128118834975 | `plain_address_policy` tests; assembler timeout/EOF/limit tests; canonical HTTP prefix tests | passed |
| REQ-PLAIN-CANDIDATE | PRX-1794128119346710 | `candidate_guard_tests` and `final_listener_candidate`; primary/fallback, mapped IP, actual port zero, distinct local service and upstream endpoint | passed |
| REQ-PLAIN-ACTIVATION | PRX-1794128119943997 | Shared parser, actual CLI binary and public Android command-line JSON conversion tests; help and LAN rejection | passed |
| Combined validation | PRX-1794128120543062 | Six affected packages, local interoperability, clippy, format, ci-gates, locked metadata, task contracts and independent review | passed |

## Observed checks

All heavy checks used `build-gate --lane ripdpi` with `CARGO_TARGET_DIR=/tmp/ripdpi-loc10-repairs-target` and `CARGO_BUILD_JOBS=4`. Each resolving Cargo command used `--locked`.

| Gate | Command | Observed result | Local log |
|---|---|---|---|
| packages | `cargo test --manifest-path native/rust/Cargo.toml --locked -p ripdpi-proxy-runtime -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-packets -p ripdpi-config -p ripdpi-proxy-config -p ripdpi-cli --lib --tests -- --test-threads=1` | exit 0, 78.6 s | `/tmp/loc10-combined-packages.log` |
| local-interop | `cargo test --manifest-path native/rust/Cargo.toml --locked -p ripdpi-proxy-runtime --test network_e2e -- --ignored --test-threads=1` | exit 0, 5.09 s | `/tmp/loc10-combined-local-interop.log` |
| clippy | `cargo clippy --manifest-path native/rust/Cargo.toml --locked -p ripdpi-proxy-runtime -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-packets -p ripdpi-config -p ripdpi-proxy-config -p ripdpi-cli --all-targets -- -D warnings` | exit 0, 0.99 s | `/tmp/loc10-combined-clippy.log` |
| format | `cargo fmt --manifest-path native/rust/Cargo.toml --all -- --check` | exit 0, 2.26 s | `/tmp/loc10-combined-format.log` |
| ci-gates | `just ci-gates` | exit 0, 44.62 s | `/tmp/loc10-combined-ci-gates.log` |
| metadata | `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1 --no-deps` | exit 0, 0.07 s | `/tmp/loc10-combined-metadata.log` |
| tasks | `./taskctl validate` | exit 0, 0.93 s | `/tmp/loc10-combined-tasks.log` |

The affected-package run reported 1154 passed and 15 existing ignored test executions. Six ignored local-fixture interoperability cases were then run explicitly and all six passed. The remaining nine are two dedicated Linux L7 capture cases and seven opt-in load/soak cases. None of the new regressions was ignored. Zero-test library/binary harnesses are not counted as coverage.

`just ci-gates` passed architecture health, LoC/hotspot budgets, cross-language contracts, fixture versions, diagnostics boundary, route coverage, locale/options/export parity and public API snapshots. The Linux-owned runtime-platform snapshot was not checked on macOS. The owning snapshot tool added only the new canonical HTTP-prefix helper to the packets API snapshot. No baseline, dependency, locale or wire schema changed.

## Regression and review evidence

- F1 original RED: blocked loopback origin read 41 HTTP bytes. HTTP and TLS raw policy regressions then passed. Review added RED cases for banner followed by blocked client data, complete oversized HTTP head, and PING passthrough; all passed after correction.
- H1: exact address reads leave client data to the common request assembler. Policy-sensitive partial HTTP/TLS is rejected on deadline/EOF/limit. Valid fragmented payload and binary passthrough tests pass.
- F2 original RED: five safety cases connected or negotiated instead of refusing self-targets; actual port-zero test observed an extra self-target request. Exact-bind review added a RED same-port distinct-local-service case. All current candidate tests and the real port-zero test passed.
- F3 original RED: real CLI, shared parser and Android conversion rejected the explicit option. All passed with `--shadowsocks-plain`; no process environment inheritance was added.
- Independent read-only review accepted each finding, their combined tree, and the rebase conflict resolution. The common relay path preserves the LOC-11 session permit transfer from origin/main.
- Rebased onto `c32e2315dfeeb60794d95c4f7d43ddf0e0b7cfae` before the final checks. The three fix commits are separate and no published history was rewritten.

## Limits and remaining scope

The guarantee is no client application bytes before payload policy. A provisional connection and inbound server banner may precede Host/SNI discovery to preserve server-first protocols. No encrypted inbound, UDP, plugin, LAN-auth expansion or UI setting was added. Encrypted Shadowsocks outbound remains separate.

The audit's pending direct-connect cancellation hypothesis is already owned by `PRX-1794125405826480` in the LOC-07 worktree. This task did not copy that uncommitted work or claim that hypothesis resolved. No physical device, external server, real secret, root activation, APK build, dedicated Linux capture lane, load/soak lane or external compatibility acceptance was run. Local fixtures are not external interoperability evidence.
