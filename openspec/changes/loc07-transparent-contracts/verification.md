---
task_id: PRX-1794125405826480
change: loc07-transparent-contracts
commit_sha: de3a5a7bd9a3ea46537a1a1a7aa5a087e65d84b0
local: passed
local_evidence: Changed-behavior gates, independent reviews, host tests, host and Linux Clippy, formatting, locked metadata and just ci-gates passed. Full Linux suites retain the environment failures listed below; they are not passing gates.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI is not an acceptance gate for this local repair. A push does not prove CI success.
device: not_applicable
device_evidence: No Android service or JNI contract changed. Physical device and root acceptance were not authorized or run.
artifact: not_applicable
artifact_evidence: No packaged Android or release artifact is published by this repair.
deployment: not_applicable
deployment_evidence: No production deployment is owned by this change.
---

# Verification

## Source and review

Final tested source: `de3a5a7bd9a3ea46537a1a1a7aa5a087e65d84b0`, on main input `82237680cfa2cbe393c89e8ac6ee45bedd3cb93d`.
Its Rust tree is `073663516fa9589624179c51bca7ec6c2a9c86d3`.

Host library tests ran on `c3b3b6a71f43bd21d93dc7f9dd1d060167d7f24c`. The only later Rust change is the network_e2e integration fixture. Library inputs did not change. The immutable input list is `/tmp/loc07-integration-logs/c3b3-rust-git-inputs.json`.

Each source repair passed a separate read-only review. F2 and the H1 syscall fixture also passed unsafe review. Final review checked both rebases: session-cap/reuse changes and the later SOCKS shared deadline/credential changes. Both Darwin fixture repairs and the route fixture repair passed separate review. No unresolved review findings remain.

| Finding | Fix commit | Result |
|---|---|---|
| F1: Transparent IPv6 policy | `1d14b83d81537039a7c3bbe9f7ff6d21a1bd41a8` | Reject disabled logical IPv6 before egress; allow canonical IPv4; preserve relay carrier family. |
| F2: IPv6 zone identity | `23f84a1042337540010c798d925e0f432681d1df` | Retain returned scope ID; retain the existing flowinfo value of zero. |
| F3: listener coverage | `4a4f97552fb1227428d3e8479918614151a0c6cd` | Use actual bind and IPV6_V6ONLY; preserve remote-to-device-local denial and the requested port-zero exception. |
| H1: pending connect shutdown | `186cc58d8218274a75e789bf91d688480b8de71d` | Stop pending connect, close its fd, suppress cancellation quality/retry signals, retain real failures and deferred TFO. |

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-LOC07-IPV6 | PRX-1794125540257589 | Real raw-byte RED/GREEN, four family cases. The complete final host runtime suite passes. | passed |
| REQ-LOC07-SCOPE | PRX-1794125540754730 | Actual production conversion RED: scope 42 became 0. Linux fd suite: 8 passed, including scoped, unscoped and IPv4 conversion. | passed |
| REQ-LOC07-SELF | PRX-1794125541322538 | Real runtime RED reproduced separate-listener failures. Final macOS 10 and Linux 12 listener cases pass: exact/wildcard/dual-stack/mapped, separate same-port services, actual prebound socket and private ATYP5. | passed |
| REQ-LOC07-CANCEL | PRX-1794125541790164 | Real pending TCP RED exceeded the 4 s drain with a live fd. Final Linux 14 cancellation/DNS/TFO cases pass. They check no forced abort or false quality failure, preserve a real refused-connect signal and verify literal TFO payload/response. | passed |
| REQ-LOC07-PRESERVE | PRX-1794125542204978 | Host libraries: 439 passed. Host network targets: 65 passed. Existing auth, policy, raw bytes, FIN, routing and unavailable-platform checks pass. Clippy on both platforms and source gates pass. Limits below remain explicit. | passed |

## Final checks and exact commands

Commands ran from the job's native/rust directory unless noted. Compiler checks used `build-gate --lane ripdpi`, at most four jobs and a target directory owned by this worktree. Host builds used sccache. Linux used pinned Rust 1.98.1, `--network none --cap-drop ALL --user 501:20 --cpus 4`, read-only dependency caches and an independent target directory. The runner is `/tmp/loc07-linux-build/run_cargo.py`; its expanded Docker argv is in each Linux log. Clippy used image `ripdpi-loc07-linux-build:20261108-clippy`; other Linux checks used `ripdpi-loc07-linux-build:20261108`.

The table includes failed full suites. A successful final batch does not make those failures pass.

| Check | Exit | Observed result | Log |
|---|---|---|---|
| combined-host-libs-rebased | 0 | test result: ok. 6 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.00s; test result: ok. 359 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 23.13s; test result: ok. 74 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.15s | `combined-host-libs-rebased.log` |
| combined-host-network-repaired | 0 | test result: ok. 2 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 5.17s; test result: ok. 10 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 6.39s; test result: ok. 39 passed; 0 failed; 6 ignored; 0 measured; 0 filtered out; finished in 23.46s; test result: ok. 9 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 2.54s; test result: ok. 5 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 1.83s | `combined-host-network-repaired.log` |
| combined-linux-libs-final | 101 | test result: FAILED. 364 passed; 2 failed; 0 ignored; 0 measured; 0 filtered out; finished in 22.24s | `combined-linux-libs-final.log` |
| combined-linux-adapter-lib | 101 | test result: FAILED. 75 passed; 1 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.12s | `combined-linux-adapter-lib.log` |
| combined-linux-cancellation | 0 | test result: ok. 2 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 5.23s; test result: ok. 2 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.31s; test result: ok. 5 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 1.90s; test result: ok. 5 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 0.12s | `combined-linux-cancellation.log` |
| combined-linux-listener | 0 | test result: ok. 12 passed; 0 failed; 0 ignored; 0 measured; 0 filtered out; finished in 6.41s | `combined-linux-listener.log` |
| combined-linux-fd | 0 | test result: ok. 8 passed; 0 failed; 0 ignored; 0 measured; 62 filtered out; finished in 4.10s | `combined-linux-fd.log` |
| combined-host-clippy | 0 | completed | `combined-host-clippy.log` |
| combined-linux-clippy | 0 | completed | `combined-linux-clippy.log` |
| combined-fmt-final | 0 | completed | `combined-fmt-final.log` |
| linux-locked-cache-fetch | 0 | completed | `linux-locked-cache-fetch.log` |

### combined-host-libs-rebased

```sh
cargo test --locked --jobs 4 -p ripdpi-proxy-runtime -p ripdpi-proxy-runtime-adapter -p ripdpi-privileged-ops --lib -- --test-threads=1
```

### combined-host-network-repaired

```sh
cargo test --locked --jobs 4 -p ripdpi-proxy-runtime --test network_e2e --test listener_coverage --test handshake_dns_cancellation --test udp_dns_cancellation --test socks4_half_close -- --test-threads=1
```

### combined-linux-libs-final

```sh
python3 /tmp/loc07-linux-build/run_cargo.py /Users/npochaev/GitHub/RIPDPI-worktrees/loc07-repairs-20261108 test --locked --offline --jobs 4 -p ripdpi-proxy-runtime -p ripdpi-proxy-runtime-adapter --lib -- --test-threads=1
```

### combined-linux-adapter-lib

```sh
python3 /tmp/loc07-linux-build/run_cargo.py /Users/npochaev/GitHub/RIPDPI-worktrees/loc07-repairs-20261108 test --locked --offline --jobs 4 -p ripdpi-proxy-runtime-adapter --lib -- --test-threads=1
```

### combined-linux-cancellation

```sh
python3 /tmp/loc07-linux-build/run_cargo.py /Users/npochaev/GitHub/RIPDPI-worktrees/loc07-repairs-20261108 test --locked --offline --jobs 4 -p ripdpi-proxy-runtime -p ripdpi-proxy-runtime-adapter --test '*cancell*' -- --test-threads=1
```

### combined-linux-listener

```sh
python3 /tmp/loc07-linux-build/run_cargo.py /Users/npochaev/GitHub/RIPDPI-worktrees/loc07-repairs-20261108 test --locked --offline --jobs 4 -p ripdpi-proxy-runtime --test listener_coverage -- --test-threads=1
```

### combined-linux-fd

```sh
python3 /tmp/loc07-linux-build/run_cargo.py /Users/npochaev/GitHub/RIPDPI-worktrees/loc07-repairs-20261108 test --locked --offline --jobs 4 -p ripdpi-privileged-ops --lib linux::tests::fd:: -- --test-threads=1
```

### combined-host-clippy

```sh
cargo clippy --locked --jobs 4 -p ripdpi-proxy-runtime -p ripdpi-proxy-runtime-adapter -p ripdpi-privileged-ops --all-targets -- -D warnings
```

### combined-linux-clippy

```sh
python3 /tmp/loc07-linux-build/run_cargo.py /Users/npochaev/GitHub/RIPDPI-worktrees/loc07-repairs-20261108 clippy --locked --offline --jobs 4 -p ripdpi-proxy-runtime -p ripdpi-proxy-runtime-adapter -p ripdpi-privileged-ops --all-targets -- -D warnings
```

### combined-fmt-final

```sh
cargo fmt --all --check
```

### linux-locked-cache-fetch

```sh
cargo fetch --locked --target aarch64-unknown-linux-gnu
```

Additional observed commands:

- `just ci-gates`: exit 0 on final source, `/tmp/loc07-integration-logs/repaired-ci-gates.log`. Architecture, file limits, native hotspots, contracts, fixture versions, diagnostics boundary, route coverage, locales, translation exports and selected API snapshots pass. The task explicitly skips the Linux-owned runtime-platform API snapshot on Darwin.
- `cargo metadata --locked --format-version 1`: exit 0 after rebase, `rebased-metadata.log`. Cargo.lock has no diff.
- `just task-check`: exit 0, `combined-task-check.log`; its npm install reported one high advisory in unchanged task-tool dependencies. No dependency edit was made.
- Final task validation and closure receipts are recorded through taskctl.

Full command history, numeric exits and elapsed times: `/tmp/loc07-integration-logs/commands.jsonl`. Rust execution summary: `/tmp/loc07-integration-logs/final-rust-validation-summary.json`.

## Failed runs and fixture repairs

The first combined host library run failed the new raw-origin fixture. Darwin inherits O_NONBLOCK on accept; SO_RCVTIMEO does not restore blocking mode. An independent libc accept/fcntl/recv probe returned EAGAIN in 7 to 8 microseconds despite a 2 s timeout. Both new origin helpers now restore blocking mode explicitly. No timeout was increased. Commits: `ddcb7120e70b0866e786d218ffcf7edd0be1e381` and `c3b3b6a71f43bd21d93dc7f9dd1d060167d7f24c`. The later complete suites pass.

The existing reset-route test had two invalid assumptions: it set a detect mask on the initial group, which the selector must skip, and it appended a third group to CLI configuration that already included an implicit fallback. The first correction observed initial 0 then advanced 2. Commit `de3a5a7bd9a3ea46537a1a1a7aa5a087e65d84b0` builds the intended two-group scenario, puts detection on the fallback, and checks the ordered literal route sequence initial 0 then advanced 1. Exact echo and the real fixture TCPReset remain asserted. The exact test and complete network target pass. Production routing did not change.

The first route-fixture commit attempt failed the 1500-source-line gate. The final assertion expresses the same ordered behavior more directly; normal hooks then passed. No limit or baseline changed. The F3 helper commit's first Clippy hook used a shared host target and failed name resolution; the unchanged source passed with its worktree-owned target. Both failed logs remain available.

The first Linux batch lacked cached pinned target dependencies and could not start tests. `cargo fetch --locked --target aarch64-unknown-linux-gnu` returned 0; Cargo.lock remained unchanged. The later offline Linux runs used the same sandbox. Queued batches cancelled before acquisition did not run tests and are not evidence.

## Environment failures and omitted checks

The final full Linux runtime suite is **failed**, 364 passed / 2 failed, exit 101:

- `strip_timestamps_does_not_replace_the_drop_sack_filter`: SO_ATTACH_FILTER returns EPERM.
- `build_udp_upstream_socket_connects_ipv4_targets`: UDP connect to 192.0.2.10:443 returns ENETUNREACH.

The final full Linux adapter suite is **failed**, 75 passed / 1 failed, exit 101:

- `upstream_udp_socket_is_protected_when_protect_path_present`: UDP connect to 203.0.113.1:443 returns ENETUNREACH.

The earlier F2 author full privileged-ops suite (`d856164baf900edc3b76df6b28afffe77462d5f4`) had 67 passed / 3 failed, exit 101. All three failures were BPF attach tests: `bpf_drop_sack_filter_attaches_and_detaches_cleanly`, `bpf_strip_timestamps_filter_attaches_and_detaches_cleanly`, `bpf_filter_detach_removes_program`. This is separate earlier evidence, not a final full-suite pass. Final fd ownership tests pass 8 with 62 explicitly filtered by the selected suite; final Linux Clippy covers all targets.

Independent controls do not link RIPDPI. A standalone C accept-all SO_ATTACH_FILTER also returns EPERM in the same sandbox (`bpf-environment-control.log`). Python UDP connect to both reserved test addresses also returns ENETUNREACH (`linux-udp-route-controls.log`); it sends no datagrams. These controls establish the environment limitations without claiming their exact kernel policy cause. No application check was weakened and no capability or network access was enabled for these tests.

Six existing network_e2e tests were ignored by the owning annotation `run by the relay interoperability lane`: chained_upstream_round_trip_records_fixture_socks_usage_end_to_end; destination_policy_block_opens_no_upstream_or_destination_connection_end_to_end; destination_policy_direct_bypasses_upstream_end_to_end; domain_resolution_policy_is_enforced_end_to_end; host_filters_only_route_matching_domain_via_upstream_end_to_end; socks5_delay_connect_replies_before_first_payload_and_round_trips. That separate lane was not run. All other selected integration targets have zero ignored tests.

Not run: successful IPv4/IPv6 netfilter REDIRECT on a privileged Linux/Android target, firewall recursion-exclusion acceptance, physical Android Proxy/VPN acceptance, external-server compatibility, packaged Android artifacts, hosted CI, Miri or sanitizers. Full Linux suites must be rerun in an authorized environment that supports the needed BPF operations and test routes. This change does not enable those privileges.

## Evidence boundary

C: reviewed code and real callers. V: only the observed local ABI, loopback and lifecycle runs above. The four-family raw test starts after the operating system would supply the original destination; it does not prove successful REDIRECT. The fd conversion test preserves an independently specified scope ID; it does not prove a kernel redirect returned one. S: the original audit's pinned kernel/source requirements remain separate. E: real deployment and external compatibility remain unverified. Static review and local loopback do not increase S or E.

The original audit and registry in Downloads remain unchanged. Automatic redirect rules, TPROXY, UDP ingress and Android VPN composition are outside this repair.

## Main integration

The user authorized separate fix commits, main integration and a remote push. Integration and remote-SHA evidence will be recorded after the first push; this section is not a push receipt yet.
