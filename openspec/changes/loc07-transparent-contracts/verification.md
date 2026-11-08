---
task_id: PRX-1794125405826480
change: loc07-transparent-contracts
commit_sha: 24abb5a0ab6f0f7055f5066184335008a426a4ee
local: passed
local_evidence: Reviewed repairs and final changes passed host tests, required Linux behavior tests, host and Linux Clippy, formatting, actual CLI probes, locked metadata and just ci-gates. Full Linux suite environment failures remain explicit below and are not passing gates.
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

## Final source and review

Current source: `24abb5a0ab6f0f7055f5066184335008a426a4ee`, on main input `930c537c89638614c7e19e6b5bbb3e5c18bab5a3`. Current Rust tree: `6765ff4ad9f962c5c97825757e2355a9dc175d0e`. Final checks and source-input reuse are in the final integration section below.

## Earlier source and review

Earlier tested source: `de3a5a7bd9a3ea46537a1a1a7aa5a087e65d84b0`, on main input `82237680cfa2cbe393c89e8ac6ee45bedd3cb93d`.
Its Rust tree is `073663516fa9589624179c51bca7ec6c2a9c86d3`.

Host library tests ran on `c3b3b6a71f43bd21d93dc7f9dd1d060167d7f24c`. The only later Rust change is the network_e2e integration fixture. Library inputs did not change. The immutable input list is `/tmp/loc07-integration-logs/c3b3-rust-git-inputs.json`.

Each source repair passed a separate read-only review. F2 and the H1 syscall fixture also passed unsafe review. Final review checked both rebases: session-cap/reuse changes and the later SOCKS shared deadline/credential changes. Both Darwin fixture repairs and the route fixture repair passed separate review. No unresolved review findings remain.

| Finding | Fix commit | Result |
|---|---|---|
| F1: Transparent IPv6 policy | `970cc50797241186aaa649828ff6992f8d2b5d80` | Reject disabled logical IPv6 before egress; allow canonical IPv4; preserve relay carrier family. |
| F2: IPv6 zone identity | `c4588955c7280a441c59124a7d933c8cdfa0323c` | Retain returned scope ID; retain the existing flowinfo value of zero. |
| F3: listener coverage | `59e331050d63add883b06acb2d7b3c251aba639b` | Use actual bind and IPV6_V6ONLY; preserve remote-to-device-local denial and the requested port-zero exception. |
| H1: pending connect shutdown | `263d5c2a7ee99d1ec01bd4eb952e9786b9605594` | Stop pending connect, close its fd, suppress cancellation quality/retry signals, retain real failures and deferred TFO. |

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-LOC07-IPV6 | PRX-1794125540257589 | Real raw-byte RED/GREEN, four family cases. The complete final host runtime suite passes. | passed |
| REQ-LOC07-SCOPE | PRX-1794125540754730 | Actual production conversion RED: scope 42 became 0. Linux fd suite: 8 passed, including scoped, unscoped and IPv4 conversion. | passed |
| REQ-LOC07-SELF | PRX-1794125541322538 | Real runtime RED reproduced separate-listener failures. Final macOS 11 and Linux 13 listener cases pass, including an independent kernel oracle for the unspecified target in the other family. Actual binding, IPV6_V6ONLY, scoped/mapped identities, private ATYP5 and final candidates share one guard. | passed |
| REQ-LOC07-CANCEL | PRX-1794125541790164 | Real pending TCP RED exceeded the 4 s drain with a live fd. Final Linux 14 cancellation/DNS/TFO cases pass. They check no forced abort or false quality failure, preserve a real refused-connect signal and verify literal TFO payload/response. | passed |
| REQ-LOC07-PRESERVE | PRX-1794125542204978 | Host libraries: 448 passed. Seven host network targets: 76 passed with 6 existing relay-lane ignores. Core Linux cases: 37 passed. The last fixture also passes on Linux (1 selected, 54 filtered); its runtime Clippy passes. Auth, policy, raw bytes, FIN, routing and real CLI unavailable-platform checks pass. Limits below remain explicit. | passed |

## Checks before the plaintext rebase and exact commands

Commands ran from the job's native/rust directory unless noted. Compiler checks used `build-gate --lane ripdpi`, at most four jobs and a target directory owned by this worktree. Host builds used sccache. Linux used pinned Rust 1.98.1, `--network none --cap-drop ALL --cpus 4` and the host UID/GID from the expanded runner argv, read-only dependency caches and an independent target directory. The runner is `/tmp/loc07-linux-build/run_cargo.py`; its expanded Docker argv is in each Linux log. Clippy used image `ripdpi-loc07-linux-build:20261108-clippy`; other Linux checks used `ripdpi-loc07-linux-build:20261108`.

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

The existing reset-route test had two invalid assumptions: it set a detect mask on the initial group, which the selector must skip, and it appended a third group to CLI configuration that already included an implicit fallback. The first correction observed initial 0 then advanced 2. Commit `de3a5a7bd9a3ea46537a1a1a7aa5a087e65d84b0` builds the intended two-group scenario, puts detection on the fallback, and checks the ordered literal route sequence initial 0 then advanced 1. Exact echo and the fixture fault receipt remain asserted. That receipt alone does not prove a TCP RST; this route test admits both reset and EOF-class failure. The exact test and complete network target pass. Production routing did not change.

The first route-fixture commit attempt failed the 1500-source-line gate. The final assertion expresses the same ordered behavior more directly; normal hooks then passed. No limit or baseline changed. The F3 helper commit's first Clippy hook used a shared host target and failed name resolution; the unchanged source passed with its worktree-owned target. Both failed logs remain available.

The first Linux batch lacked cached pinned target dependencies and could not start tests. `cargo fetch --locked --target aarch64-unknown-linux-gnu` returned 0; Cargo.lock remained unchanged. The later offline Linux runs used the same sandbox. Queued batches cancelled before acquisition did not run tests and are not evidence.

## Environment failures and omitted checks

The pre-plaintext-rebase full Linux runtime suite at `de3a5a7b` is **failed**, 364 passed / 2 failed, exit 101:

- `strip_timestamps_does_not_replace_the_drop_sack_filter`: SO_ATTACH_FILTER returns EPERM.
- `build_udp_upstream_socket_connects_ipv4_targets`: UDP connect to 192.0.2.10:443 returns ENETUNREACH.

The pre-plaintext-rebase full Linux adapter suite at `de3a5a7b` is **failed**, 75 passed / 1 failed, exit 101:

- `upstream_udp_socket_is_protected_when_protect_path_present`: UDP connect to 203.0.113.1:443 returns ENETUNREACH.

The earlier F2 author full privileged-ops suite (`d856164baf900edc3b76df6b28afffe77462d5f4`) had 67 passed / 3 failed, exit 101. All three failures were BPF attach tests: `bpf_drop_sack_filter_attaches_and_detaches_cleanly`, `bpf_strip_timestamps_filter_attaches_and_detaches_cleanly`, `bpf_filter_detach_removes_program`. This is separate earlier evidence, not a final full-suite pass. The fd ownership suite passed 8 with 62 explicitly filtered by its selection; this is not a full-suite pass. The final privileged-ops source did not change, and Linux Clippy covers all targets.

Independent controls do not link RIPDPI. The first controls used UID/GID 501:20. The final controls were rerun with the exact host UID/GID from the runner argv, the same image, network mode and capabilities (`final98-exact-sandbox-controls.log`). A standalone C accept-all SO_ATTACH_FILTER on both unbound and connected loopback TCP sockets returns EPERM. Python UDP connect to both reserved test addresses returns ENETUNREACH and sends no datagrams. The independent C source is `final98-sandbox-control.c`. These controls establish the environment limitations without claiming their exact kernel policy cause. No application check was weakened and no capability or network access was enabled for these tests.

Six existing network_e2e tests were ignored by the owning annotation `run by the relay interoperability lane`: chained_upstream_round_trip_records_fixture_socks_usage_end_to_end; destination_policy_block_opens_no_upstream_or_destination_connection_end_to_end; destination_policy_direct_bypasses_upstream_end_to_end; domain_resolution_policy_is_enforced_end_to_end; host_filters_only_route_matching_domain_via_upstream_end_to_end; socks5_delay_connect_replies_before_first_payload_and_round_trips. That separate lane was not run. All other selected integration targets have zero ignored tests.

Not run: successful IPv4/IPv6 netfilter REDIRECT on a privileged Linux/Android target, firewall recursion-exclusion acceptance, physical Android Proxy/VPN acceptance, external-server compatibility, packaged Android artifacts, hosted CI, Miri or sanitizers. Full Linux suites must be rerun in an authorized environment that supports the needed BPF operations and test routes. This change does not enable those privileges.

## Evidence boundary

C: reviewed code and real callers. V: only the observed local ABI, loopback and lifecycle runs above. The four-family raw test starts after the operating system would supply the original destination; it does not prove successful REDIRECT. The fd conversion test preserves an independently specified scope ID; it does not prove a kernel redirect returned one. S: the original audit's pinned kernel/source requirements remain separate. E: real deployment and external compatibility remain unverified. Static review and local loopback do not increase S or E.

The original audit and registry in Downloads remain unchanged. Automatic redirect rules, TPROXY, UDP ingress and Android VPN composition are outside this repair.

## Final integration after the plaintext changes

Main advanced while checks ran. The rebase onto `930c537c` had four F3 conflicts, resolved and reviewed before continuation. The newer PlainAddress flow and final-candidate guard remain. Actual listener facts have one owner, `ListenerBinding`; configured port zero stays unchanged. The final candidate guard delegates to the same coverage predicate as ingress. F1 stays specific to the logical Transparent destination; it does not gate the relay carrier or PlainAddress.

A further F3 regression was reproduced on Darwin: a direct kernel roundtrip to `[::]:P` reaches a separate `[::1]:P` origin, but the old exact IPv4 proxy denied the same target with SOCKS REP2. The added test compares literal bytes through the kernel and then through the proxy. The guard now checks actual family coverage before treating an unspecified target as self. This is proof of the shared TCP guard at the SOCKS boundary, not a successful Transparent REDIRECT run.

The later full network target exposed an existing reset-learning test with an invalid stimulus. Its accept-time generic reset could produce EOF before HTTP Host; the failed run's store had one host-bearing failure, while the learner requires two. Commit `24abb5a0ab6f0f7055f5066184335008a426a4ee` moves that case to the existing block-signal module. Both servers read and assert complete independent HTTP literals before the first SO_LINGER0 close, then echo retries. Exactly three unique groups replace implicit CLI defaults. The original blocked-host count of 1, penalty, learned count and host_blocked assertions remain. No exact learned-winner assertion existed or is claimed. The exact case and complete network target pass; no production learner or threshold changed.

One attempted test invocation inherited a shared host target. It was stopped with exit 130 and is not evidence. All accepted runs and commit hooks use the explicit worktree target and Cargo home.

A partial-rebase Linux compile failed E0463 before running a test. Read-only checks found identical rustc binaries across images and present, internally consistent sampled dependency metadata. The final frozen no-run build and ordinary tests passed without cache deletion or source repair. The exact cause of that earlier compiler failure remains unestablished; it is not an application finding.

Final production-code validation ran on `98cb3856d389407d3f82d3c3cf56de88fcda311f`. The only Rust differences to `24abb5a0` are the two reset-learning integration test files. The immutable input record is `/tmp/loc07-integration-logs/final98-rust-git-inputs.json`. All library and other integration-target inputs remain unchanged.

| Check | Observed result | Evidence |
|---|---|---|
| Full host libraries | 6 privileged-ops + 368 runtime + 74 adapter = 448 passed; no failures, ignores or filters | final98-rust-validation-summary.json |
| Seven host network targets | final-candidate 1; handshake DNS 2; listener 11; network_e2e 39; plain policy 9; SOCKS4 FIN 9; UDP DNS 5 = 76 passed; 6 existing relay-lane ignores | final98-host-network.log plus final98-block-signal-network-green.log and remaining-target logs |
| Linux cancellation | 14 passed, no failures or ignores | final98-rust-validation-summary.json |
| Linux listener and new shared paths | listener 13 + final-candidate 1 + plain policy 9 = 23 passed, no failures or ignores | final98-rust-validation-summary.json |
| Host and Linux Clippy | Three affected packages, all targets, -D warnings; exit 0 | final98-rust-validation-summary.json |
| Final host reset-learning fixture | Exact 1 passed; complete target 39 passed / 6 existing ignored; fmt and all normal hooks passed | final98-block-signal-*.log |
| just ci-gates on 24abb5a0 | exit 0 | final24-ci-gates.log |
| Locked metadata on 24abb5a0 | exit 0; no Cargo.lock edit | final24-metadata.log |
| Real CLI on 24abb5a0 | Build/help 0; -E IPv4 and --transparent IPv6 start; 16 raw bytes, zero response bytes; Unsupported log; SIGTERM exit 0 without force; wildcard bind exit 1 | final24-cli-availability.json |
| Final Linux reset-learning fixture and runtime Clippy | exact case 1 passed, 54 filtered, no failures or ignores; runtime all-target Clippy exit 0 | final24-cli-and-linux-delta-summary.json |

The real CLI bytes are `00ff050100434f4e4e4543540d0a0400`. They are supplied independently by the temporary client. No auth data or upstream reply is inserted. Both CLI stop runs took about 0.038 s after the client closed; this is not a pending-connect timing measurement.

The complete final98 summary contains exact argv and numeric exits. Final24 CLI argv, bytes, logs and stop receipts are in `final24-cli-availability.json`. Log paths in this section are under `/tmp/loc07-integration-logs/`.


### Exact commands for the final changes

Host commands use `CARGO_HOME=/Users/npochaev/.cargo`, the explicit job `CARGO_TARGET_DIR`, sccache and four jobs. Linux keeps the runner sandbox described above. The runtime-only final Clippy checks the two changed test files; the other two packages retain their prior unchanged-input checks.

```sh
cargo build --locked --jobs 4 -p ripdpi-cli
```

Exit 0; log `final24-cli-build.log`.

```sh
python3 /tmp/loc07-cli-availability.py
```

Exit 0; log `final24-cli-availability.log`.

```sh
python3 /tmp/loc07-linux-build/run_cargo.py /Users/npochaev/GitHub/RIPDPI-worktrees/loc07-repairs-20261108 test --locked --offline --jobs 4 -p ripdpi-proxy-runtime --test network_e2e block_signal::repeated_tcp_resets_confirm_blocked_host_and_expose_telemetry -- --exact --nocapture --test-threads=1
```

Exit 0; log `final24-linux-reset-fixture.log`.

```sh
python3 /tmp/loc07-linux-build/run_cargo.py /Users/npochaev/GitHub/RIPDPI-worktrees/loc07-repairs-20261108 clippy --locked --offline --jobs 4 -p ripdpi-proxy-runtime --all-targets -- -D warnings
```

Exit 0; log `final24-linux-clippy-runtime.log`.

CLI process argv (the temporary client owns and releases these loopback ports):

```sh
/Users/npochaev/GitHub/RIPDPI-worktrees/loc07-repairs-20261108/native/rust/target/debug/ripdpi --help
```

Exit 0.

```sh
/Users/npochaev/GitHub/RIPDPI-worktrees/loc07-repairs-20261108/native/rust/target/debug/ripdpi -E --ip 127.0.0.1 --port 61663 --max-conn 2 --debug 2
```

Exit 0.

```sh
/Users/npochaev/GitHub/RIPDPI-worktrees/loc07-repairs-20261108/native/rust/target/debug/ripdpi --transparent --ip ::1 --port 61668 --max-conn 2 --debug 2
```

Exit 0.

```sh
/Users/npochaev/GitHub/RIPDPI-worktrees/loc07-repairs-20261108/native/rust/target/debug/ripdpi --transparent --ip 0.0.0.0 --port 61673 --max-conn 2
```

Exit 1.

The temporary client sends the recorded 16 bytes only after readiness, reads until EOF/reset with a deadline, and then sends SIGTERM. Its source and machine-readable observations remain in /tmp. The wildcard rejection intentionally has exit 1. Neither run is a positive original-destination lookup.

## LOC-07 scope boundary

| Variant or requirement | Implementation and reachability | Evidence and limit | State |
|---|---|---|---|
| IPv4 original destination | Linux/Android SO_ORIGINAL_DST, first lookup | C + local fd ABI tests; no real redirected flow | implemented; live acceptance not established |
| IPv6 original destination and fallback | IPv6 option after IPv4 failure; returned zone retained | C + scoped/unscoped ABI test; no invented interface | implemented; live acceptance not established |
| Unsupported host platform | Fail closed before routing | Actual macOS CLI IPv4/IPv6, empty reply | explicitly unsupported, fallback verified |
| Redirect setup and recursive capture exclusion | External OS rules and exclusion are required | No rules or root activated; automatic setup outside scope | intentionally external |
| Self-loop, wildcard, v6-only, mapped and scoped binding | One actual listener coverage guard, also at final candidates | 11 macOS / 13 Linux listener cases, final-candidate and unit cases | implemented |
| Unspecified target in another family | Family coverage before self classification | Independent kernel bytes then real proxy bytes | implemented in shared guard |
| Auth and remote admission | Transparent has no parser auth; token or non-loopback composition is rejected | Config/state tests, actual CLI wildcard denial; actual bind drives private ATYP5 gate | implemented |
| IPv6 and address policy | Canonical logical family checked before routing; block gates retained | Raw four-case test and full host policy tests; carrier family remains separate | implemented |
| Routing, upstream and close | Final candidates guarded; pending connect stops; real errors retained | Real loopback, DNS/TFO/pending-fd and FIN cases; no false cancellation quality signal | implemented within local evidence |
| CLI availability | Both flags reach the real runtime | Real build, startup, raw client, stop and rejection checks | locally verified; positive REDIRECT unavailable on this host |
| Android Proxy / VPN | Command-line Proxy can select Transparent; authenticated normal VPN composition rejects it | Current code/caller boundary from the original audit; no device or rules activated | C only; device acceptance not established |

## Main integration

The user authorized separate fix commits, main integration and a remote push. Integration and remote-SHA evidence will be recorded after the first push; this section is not a push receipt yet.
