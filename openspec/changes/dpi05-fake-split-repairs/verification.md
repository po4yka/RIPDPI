---
task_id: ENG-1794499735949864
change: dpi05-fake-split-repairs
commit_sha: 547e5a172e42e6550106a9ade80df38258dc95c8
local: passed
local_evidence: Six findings are closed by published common fixes plus the owned profile, context and oracle changes. Final runtime163, adapter36, platform53, planner166, loopback2, fakeTCP6, payload5, rawpacket22 and protocol55 tests passed. Seven-crate all-target Clippy, API113, just ci-gates, locked metadata, oracle15 and shell checks passed. Full proxy/helper sandbox failures are recorded below.
remote_ci: not_applicable
remote_ci_evidence: No hosted CI run is claimed. Local integration and push are tracked separately from CI.
device: not_applicable
device_evidence: Physical or privileged device activation was not authorized. Android wire compatibility is not claimed.
artifact: passed
artifact_evidence: Full arm64-v8a Android instrumentation compilation with native builds passed on a byte-checked isolated copy; BUILD SUCCESSFUL in5m14s. The later receipt correction changes only cfg(test) code and retains the compiled production source.
deployment: not_applicable
deployment_evidence: No release or deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-DPI05-PROGRESS | ENG-1794499996971989 | Published page/progress/no-replay fixes; two actual partial-splice/wait peer-byte tests; runtime safety suite | Passed locally |
| REQ-DPI05-PROFILES | ENG-1794500005944193 | Four-order/two-sequence profile matrix, executor handoff and loopback tests | Passed locally |
| REQ-DPI05-MD5 | ENG-1794500007476648 | Common fake-only packet builder and independent IPv4/IPv6 byte/option checks | Passed locally |
| REQ-DPI05-TTL | ENG-1794500008685404 | Explicit genuine TTL and published normal/disorder packet-byte and runtime checks | Passed locally |
| REQ-DPI05-ORACLE | ENG-1794500010219878 | Independent fifteen-test oracle suite, shell entry and full Android compilation | Passed locally; live capture not authorized |
| REQ-DPI05-FAMILY | ENG-1794500011657144 | Actual peer A bytes and second-region family/fallback/progress regression | Passed locally |

## Closure matrix

The audit base was `69502620c30cf97528158a37f12b7e2724f527c8`. The consolidated integration base is `dd77220f9a7f4f0579c24aaf3d4d33670cbda332`. Concurrent main changes supplied the common F1/F3/F4 fixes. Three read-only reviewers confirmed that those implementations satisfy the original findings. This task uses them and keeps one owner for progress, uncertainty and genuine TTL.

| Finding / requirement | Implementation and commit | Observed evidence | State |
|---|---|---|---|
| F1 / REQ-DPI05-PROGRESS | Published `e1b25b59c2` restores real pages before cleanup; `4d841bda2a` marks opaque fake/ordered failures uncertain and prevents replay. Owned `ad0c328158` adds two real partial-splice/wait regressions | Actual unprivileged Linux peer reads confirm real bytes, exact known stream progress and restored TTL; runtime safety and proxy classification tests cover opaque failures | Implemented; local V, root wire not run |
| F2 / REQ-DPI05-PROFILES | `74d817105e` preserves primary then secondary within both fake regions and counts every fake in sequential offsets | Four orders by two sequence modes; executor handoff and genuine loopback stream checks | Implemented; local V |
| F3 / REQ-DPI05-MD5 | Published `ec34385184`, common packet builder: raw-original MD5=false; ordered MD5 only for fake role | 22 owning raw-packet tests include IPv4/IPv6 bytes, options and checksums; raw fake/payload suites also pass | Implemented; packet-construction V, root wire not run |
| F4 / REQ-DPI05-TTL | Published `ec34385184` separates genuine TTL through `original_ttl`; disorder explicitly supplies TTL1. Published `bfbb497c0e` records that ownership | Owning normal/fake/original TTL byte tests and runtime disorder tests pass; no duplicate socket-TTL wrapper is added | Implemented; local V, root wire not run |
| F5 / REQ-DPI05-ORACLE | `c9222ee526` adds deterministic eight-mode source bytes, strict wire oracle, Android fixture and negative cases | 15 Python tests, owning shell tests, actual tshark over synthetic IPv4/IPv6 PCAPs, full Android compile | Implemented; oracle V; actual Android capture not run |
| F6 / REQ-DPI05-FAMILY | `bfac92ed28` passes the caller family/fallback to the second region | Failure regression observes peer A bytes and checks recognized family, fallback and prior progress | Implemented; local V |

Source owners: `ripdpi-privileged-ops/src/linux/raw_packet/fake_tcp.rs:133`, `packet_builder.rs:255`, `ripdpi-desync-runtime/src/transport_io/strategy_actions.rs:122`, `tcp_fake_family/fake_disorder.rs:177`, `tcp_fake_family/fake_split.rs:66`, and `ripdpi-proxy-runtime/src/runtime/state/failure.rs:75`, all below `native/rust/crates/`.

The two superseded private `TcpSendError` hierarchies and the temporary socket-TTL wrapper were removed from the unpublished branch. The published common contracts remain unchanged. The unique splice/wait regressions were retained. No dependency, baseline, golden, locale, protobuf or helper wire-schema change is added by this task. Existing main protocol/bulk-frame changes are inherited.

## Ordering and availability matrix

Let F_A/F_B mean the fake group for each region; each TLS group contains primary then secondary when a secondary profile is configured; otherwise it contains the primary profile only. G_A/G_B are the genuine split regions.

| altorder | Required first-emission order | duplicate | sequential | Gate and evidence |
|---|---|---|---|---|
| 0 | F_A, G_A, F_B, G_B | Supported | Supported | Basic duplicate mode can use Linux/Android stream transport when no raw-only options are selected. Sequential/raw variants need TCP_REPAIR/raw access. Unit/executor tests cover both; full wire fixture is compiled only |
| 1 | F_A, F_B, G_A, G_B | Supported | Supported | Ordered raw sender; unit/executor tests and independent oracle cases cover both sequence modes |
| 2 | G_A, F_A, G_B, F_B | Supported | Supported | Same raw gate and evidence |
| 3 | G_A, G_B, F_A, F_B | Supported | Supported | Same raw gate and evidence |

The raw acceptance asset uses deterministic HTTP compat-profile bytes. TLS secondary grouping has native profile/executor coverage; no live TLS-secondary wire result is claimed.

Duplicate mode reuses each target region's original sequence. Sequential mode adds all earlier fake byte lengths; genuine offsets stay unchanged. The non-root Linux tests use real mmap/vmsplice/splice and peer sockets with UID501 and no effective capabilities. They prove low-level stream availability and error cleanup on that kernel. They do not prove Android UID/SELinux availability or the full Android default chain. Custom orders, secondary/timestamp raw features and exact original flags require raw capabilities or the opt-in helper. Permissions do not follow from an enum or compile-time capability flag.

The existing boundary/activation behavior from main is preserved: offsets beyond the payload are clamped by the planner, default unfiltered activation is round1, explicit ranges remain effective, and inactive/terminal cases keep the payload. These are inherited contracts, not additional DPI-05 features.

## Exact checks and outcomes

Host checks on the consolidated source:

- `just ci-gates` passed: architecture/LoC/hotspot, contracts, fixture versions, diagnostics boundaries, route coverage, locales, translation export and API checks. No baseline was extended.
- `cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1`, `cargo fmt --manifest-path native/rust/Cargo.toml --all -- --check` and `git diff --check` passed. Commit hooks passed.
- `python3 -m unittest discover -s scripts/tests -p test_fakedsplit_wire_oracle.py`:15 passed. `bash scripts/ci/test-android-packet-smoke-lib.sh` and `bash -n scripts/ci/run-android-packet-smoke.sh` passed.
- The isolated F5 run used real tshark with independently generated synthetic IPv4/IPv6 PCAPs: all eight correct cases passed and checksum-correct genuine RST variants failed. This is oracle validation, not a capture from an Android emitter.

Linux runtime commands used Rust1.98.1, `--locked --offline`, four build jobs, read-only source/dependencies and image `ripdpi-dpi05-linux-check:rust1.98.1`. Docker retained `--network none --cap-drop ALL --security-opt no-new-privileges --user 501:20 --read-only --tmpfs /tmp:rw,size=2g`.

```sh
cargo +1.98.1 test --locked --offline --no-fail-fast --lib -p ripdpi-desync-runtime -p ripdpi-runtime-platform -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-proxy-runtime
cargo +1.98.1 test --locked --offline -p ripdpi-privileged-ops linux::tests::fake_send_failures:: -- --nocapture
cargo +1.98.1 test --locked --offline -p ripdpi-privileged-ops linux::raw_packet::fake_tcp::tests -- --nocapture
cargo +1.98.1 test --locked --offline -p ripdpi-privileged-ops linux::raw_packet::tcp_payload:: -- --nocapture
cargo +1.98.1 test --locked --offline -p ripdpi-privileged-ops linux::tests::raw_packet -- --nocapture
cargo +1.98.1 test --locked --offline -p ripdpi-desync --lib
cargo +1.98.1 test --locked --offline -p ripdpi-root-helper-protocol
cargo +1.98.1 test --locked --offline -p ripdpi-root-helper --bin ripdpi-root-helper
cargo +1.98.1 clippy --locked --offline -p ripdpi-desync-runtime -p ripdpi-privileged-ops -p ripdpi-runtime-platform -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-proxy-runtime -p ripdpi-root-helper -p ripdpi-root-helper-protocol --all-targets -- -D warnings
```

| Linux suite | Observed result |
|---|---|
| runtime initial consolidated run | 162 passed,1 failed due to an incorrect bounded-receipt expectation |
| runtime after test correction | 163 passed,0 failed; targeted test1 passed |
| adapter / runtime-platform | 36 / 53 passed |
| actual partial-splice and wait loopbacks | 2 passed before commit and in the full batch |
| fake TCP / TCP payload / raw packet oracle | 6 / 5 / 22 passed |
| planner | 166 passed |
| root-helper-protocol | 42 +7 +3 +3 passed; doc tests0 |
| proxy-runtime | 398 passed,2 known sandbox failures |
| root-helper unit binary | 59 passed,8 known noexec fixture failures |
| seven-crate all-target Clippy | exit0 |
| Linux owning API helper | exit0;113 lines match committed snapshot |

No new test was ignored. Filtered counts and child-process summaries are not added to these counts.

The additional test correction is separate commit `547e5a172e`. `plain_fallback_failures_keep_partial_progress_and_configured_marker` compared the bounded receipt to full peer progress (observed65535 vs80357). The exact `error.bytes_committed()==received.len()` check had passed and remains unchanged. The receipt expectation now uses the documented u16 bound. Independent review confirmed the contract; the targeted test and full runtime library then passed. This correction changes cfg(test) code only.

The two proxy failures are BPF attach `EPERM` in `strip_timestamps_does_not_replace_the_drop_sack_filter` and TEST-NET `ENETUNREACH` in `build_udp_upstream_socket_connects_ipv4_targets`. Both were reproduced on the audit base in the same sandbox. Earlier WS timing failures passed isolated repeats and are absent from the final consolidated full run.

The eight helper failures are unchanged nfqws executable-fixture cases: `initial_program_discovery_failure_keeps_recovery_pending`, `failed_discovery_and_partial_legacy_cleanup_are_retried_before_activation_and_shutdown`, `a_start_removes_only_the_chains_that_carry_its_own_names`, `the_reap_lists_through_a_held_lock_and_removes_dead_helper_chains`, `a_hung_firewall_listing_ends_at_the_one_reap_deadline`, `firewall_effect_then_timeout_and_failure_before_effect_are_reconciled`, `firewall_retries_a_held_xtables_lock_within_the_deadline`, and `pending_cleanup_retries_back_off_instead_of_running_on_every_poll`. The sandbox mount reports `/tmp` as noexec and a test-owned executable returns EACCES13. Permissions were not relaxed. Full proxy/helper commands remain exit101, not passing suites.

The Linux API check used the unchanged owning `scripts/ci/check_rust_api_snapshots.py:cargo_public_api` helper with installed nightly tooling. Runtime tests/Clippy used pinned stable. The113-line snapshot SHA-256 is `189b36c4cc2fcaefd85f70b84a331c432a28c4762b086b95b2b6aa39705e6cc5`.

The consolidated before/after eight-crate digest was `4d0aadbe933c303af48f3082d44c0c5f612d890a0a00149e73d2f04002b2db92` over449 Rust files at `ad0c328158658af78d94721b7f9ecb2d564ea687`. Only the documented test assertion changed afterward. Exact Docker commands/logs/receipts are in `/tmp/ripdpi-dpi05-linux-build/consolidated/{loopback,full}`. Targeted/final runtime results are in `/tmp/dpi05-receipt-fix-results.json`.

## Android artifact

`./gradlew :app:compileGithubFullDebugAndroidTestKotlin --console=plain` passed with native builds enabled, arm64-v8a: BUILD SUCCESSFUL in5m14s;261 tasks,45 executed and216 up-to-date. Source-copy verification matched14032 regular files before the run; superseded files were removed from this task-owned copy only after preimage checks. Outputs remain scoped to that worktree. The final test-only receipt correction does not change compiled production inputs.

Logs/receipts: `/tmp/dpi05-consolidated-android.log`, `/tmp/dpi05-consolidated-android.exit`, `/tmp/dpi05-consolidated-source-copy.json`, `/tmp/dpi05-consolidated-results.json`. The earlier isolated F5 build also passed after the owning fetch script supplied a missing pinned arm64 NaiveProxy artifact; no pin or manifest changed.

## Execution limitations and recovery

One earlier batch stopped during planner compilation with ENOSPC. First-run logs were preserved. Retired task-only raw/integration outputs were removed; shared caches and other work were preserved. Docker Desktop's metadata filesystem had become read-only. Only this task's failed container was active; Docker Desktop was restarted with images/volumes retained. The four unfinished checks were resumed on the identical source and passed; receipts are in `/tmp/ripdpi-dpi05-linux-build/resume-enospc`. The final consolidated batch above was then run on the adopted shared implementation. Storage failures are not reported as test assertions or passes.

## Evidence limits and unrun acceptance

C: six reviewed code findings are closed. V: local loopback bytes, packet construction, unit behavior, oracle validation and compilation are observed. S: no new external specification compliance claim is added. E: no external compatibility claim is added. The original pre-fix audit remains `/Users/npochaev/Downloads/RIPDPI_network_audits/DPI-05.md`.

Not run: physical-device/root-helper activation, the eight-mode Android raw capture and external server/network acceptance. The exact unrun entry below requires explicit root/device authorization and the documented device or emulator-pool lease first:

```sh
: "${AUTHORIZED_SERIAL:?Set an explicitly authorized leased serial}"
ANDROID_SERIAL="$AUTHORIZED_SERIAL" RIPDPI_PACKET_SMOKE_SCENARIO_FILTER=android_proxy_fakedsplit_family RIPDPI_PACKET_SMOKE_CAPTURE_MODE=raw bash scripts/ci/run-android-packet-smoke.sh
```

Acceptance requires source bytes, packet ranges, first-emission order, sequence, TTL, options and peer stream checks. An indirect capture or a low-TTL-only packet is not full evidence. BPF, UDP-route and executable-fixture cases also need a suitable authorized test host. No hosted CI or real-secret test is claimed.
