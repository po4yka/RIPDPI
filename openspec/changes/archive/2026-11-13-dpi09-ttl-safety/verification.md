---
task_id: ENG-1794557091175687
change: dpi09-ttl-safety
commit_sha: "99157964becd971daac2448ed073cfea25f60b57"
local: passed
local_evidence: At commit_sha, 736 host library tests, seven Disoob oracle tests, workspace all-target Clippy and task contracts passed; source gates passed before a metadata-only rebase; Linux runtime passed 170 and TCP stage module passed 14 tests; independent reviews found no blocker.
remote_ci: not_applicable
remote_ci_evidence: No hosted CI run is claimed or required as local repair evidence.
device: not_applicable
device_evidence: No Android device, physical transport, external server or root activation was part of this repair.
artifact: not_applicable
artifact_evidence: No APK or native release artifact is published by this change.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-OOB-TTL-RESTORE | ENG-1794557266916478 | Real Linux TTL setter error and read-back; caller progress retained; Linux runtime suite | passed |
| REQ-OOB-TTL-FAMILY | ENG-1794557268725615 | Native IPv6 and mapped-peer socket tests; inactive family remains unchanged | passed |
| REQ-OOB-STAGE-DEADLINE | ENG-1794557269707410 | Real Linux stalled peer; both wait modes time out; accepted source bytes are preserved | passed |
| REQ-OOB-WIRE-EVIDENCE | ENG-1794557270566971 | Seven independent packet-oracle tests and strict echo wired into the named smoke scenario; full runtime capture not run | passed |
| REQ-OOB-PLATFORM-SCOPE | ENG-1794557271420486 | Both urgent-method platform rows reviewed against the native wait adapter | passed |
| REQ-OOB-TTL-RESTORE | ENG-1794557272429801 | Combined locked tests, workspace Clippy, source gates and independent reviews | passed |

## Audit closure map

The DPI-09 audit used main at 7cdc4bd49de465a85058236132ecba3597b76638.
The repair started at 2d74ada2c26d38423439c3e00bd612f312cf3f84 and was rebased
on 2824082bff0428eb269e0576712174f6f6a91776 before the final integration checks.

| Audit item | Repair commit | Observed evidence or current contract | State |
|---|---|---|---|
| F1 partial OOB progress | Existing main 8c48353d1e, 326e396ea6 | Progress-tracked prefix and one urgent byte; actual peer-byte regressions rerun in the runtime suites | closed before this change |
| F2 multiple urgent marks | Existing main b3a51e8971 | One urgent action in the initial original buffer; later urgent steps become Split; planner and peer tests rerun | closed before this change |
| F3 ignored repeat and skip | Existing main 197df0b548, c9d964d440 | Unsupported urgent repeats and skip rejected by CLI, JSON, Kotlin and native planner; native suites rerun | closed before this change |
| H3 generic urgent projection | Existing main 398e05e47c | Typed WriteUrgent projection regression rerun | closed before this change |
| H2 active TTL family | cd041db341586e994f9c54824b8e633814b86081 | Getter and setter share the OS-correct active-family choice, including mapped peers | closed |
| H1 restore failure | 1f0ec898d1655ce58337f1fe923fb0d86ac88a8d | A rejected restore returns an error instead of TTL-unavailable success | closed |
| F4 smoke oracle | 143b37ef661e1baea8af7607a808994cef1edca3 | Strict local echo; sequence-aware byte, urgent-pointer and TTL checks | closed in code and oracle tests |
| H4 stage deadline | 84856475214c57d73a1b32bcf25dd5e52a82f77a | A blocked send queue returns TimedOut in both modes | closed |
| F5 platform claim | 4dd62baf9150b62d0c1bd7b8c1755f7f3e987ae1 | Oob and Disoob state Linux/Android; host-test limits are explicit | closed |

## Reproductions and limits

- H1: the original restore branch returned Ok(false) for a real Linux EINVAL
  from TTL 0. The test build exercises the Android-ignore errno branch. The
  fixed helper returns the error, keeps the caller-supplied committed count,
  and leaves cleanup available. This is not an observed 17-byte end-to-end send
  or a test of an Android policy that permits lowering but refuses restoration.
- H2: the old setter changed the inactive IPv4 TTL on an IPv6 socket from 37 to
  42. A mapped-peer getter returned IPv6 Hop Limit 51 instead of IPv4 TTL 37.
  Both old-code tests failed and the fixed Linux tests passed. Linux/Android
  canonicalize mapped peers; other hosts retain the native socket namespace.
  Mapped wire TTL was not captured.
- H4: with a real stalled loopback peer and an unsent queue, the old true mode
  returned Ok after the deadline while false mode timed out. The red run failed
  after 10.02 seconds. The green module ran 14 tests with no failures or ignored
  cases, including both wait modes and exact accepted peer bytes.
- F4: the new oracle accepts separate or coalesced urgent segments, consistent
  retransmission, capture reordering and sequence wrap. It rejects wrong bytes,
  pointer, TTL, gaps and conflicting overlaps. An adversarial test shows that
  the actual old disjoint URG/TTL helpers accept unrelated evidence which the
  new oracle rejects. Seven tests passed, with no ignored cases.
- The initial macOS TTL 0 test was excluded: macOS permits TTL 0, so that run
  proves neither restore failure nor address-family masking. Compiler E0583
  found the integration and nested module paths; explicit path attributes fixed
  those lookup errors before the final seven-test pass.

## Final combined checks

Commands used Rust 1.98.1, a target isolated to the integration worktree,
four Cargo/CMake jobs and a top-level build-gate lease. After another RIPDPI
batch held its lane for over 30 minutes, the user explicitly allowed this task
to borrow the free rly14 lane. No permanent gate setting, held marker, resource
limit or unrelated process was changed. Only our own queued waiters were
cancelled before admission; those cancelled attempts are not test evidence.

```sh
cargo test --manifest-path native/rust/Cargo.toml --locked --jobs 4   -p ripdpi-config -p ripdpi-proxy-config -p ripdpi-desync   -p ripdpi-desync-runtime -p ripdpi-privileged-ops --lib
cargo test --manifest-path native/rust/Cargo.toml --locked --jobs 4   -p ripdpi-cli --test packet_smoke 'disoob::tests::' -- --nocapture
cargo clippy --manifest-path native/rust/Cargo.toml --locked --jobs 4   --workspace --all-targets -- -D warnings
just ci-gates
./taskctl validate
cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1
```

Results at the recorded code SHA:

- Host libraries: config 190, desync 185, desync-runtime 183, privileged-ops 6,
  proxy-config 172: 736 passed, zero failed or ignored.
- Oracle: seven passed, zero failed or ignored; 26 unrelated tests filtered.
- Workspace all-target Clippy: exit 0, warnings denied.
- Source gates: exit 0. They include architecture health, LoC/hotspot budgets,
  cross-language contracts, locale/translation parity and Rust API snapshots.
  The gate reports the unchanged runtime-platform snapshot as Linux-owned and
  skips that canonical snapshot on Darwin by design.
- Task contracts: passed. Locked Cargo metadata: 113 workspace members; no
  manifest or lockfile input changed after that check.
- General independent review cleared F4/F5 after module-path correction and
  H1/H2/H4 without a blocking finding. The scoped safety review cleared H4:
  zero added or changed unsafe blocks, pointers, layouts, ABI or fd lifetimes.
  Miri was not run; the changed property requires the real Linux kernel queue.
- Every repair commit ran the normal selected pre-commit hooks without bypass.

The first combined tree at 23d5ebc6f5b22e87e898c645eabb718bc518f9dd passed
722 library tests, seven oracle tests and the same Clippy/source/task gates.
The later tlsrandrec and DPI-13 commits changed shared planner/config inputs,
so affected gates were repeated after rebase. Final counts above use the final
integration run at commit_sha. Source gates passed at
302f0097af2bd4821bfdcad84ff023e5e9cee990. The next rebase included only the
DPI-13 task archive and deletion; native/rust had no diff. The merged board was
stale and was regenerated through taskctl; task validation then passed. Native
tests, oracle and workspace Clippy were also repeated at commit_sha. No source
gate input changed after its successful run, apart from task lifecycle metadata
which passed its owning validation.

## Local evidence files

Temporary logs are not published test fixtures:

- /private/tmp/dpi09-integrated-results.json and dpi09-integrated-*.log: final native and task checks.
- /private/tmp/dpi09-merged-source-gates.log: final successful source gates before the metadata-only rebase.
- /private/tmp/dpi09-rebased-results.json and dpi09-rebased-*.log: earlier combined run.
- /private/tmp/dpi09-runtime-h2-before.log: inactive-family red.
- /private/tmp/dpi09-runtime-linux-before-extra.log: genuine restore and mapped-getter reds.
- /private/tmp/dpi09-runtime-linux-after-batch.log: Linux runtime 170 passed.
- /private/tmp/dpi09-runtime-green-commit-rly14.log: runtime green and both normal commit hooks.
- /private/tmp/ripdpi-dpi09-stage-old.log: stalled-peer red.
- /private/tmp/ripdpi-dpi09-stage-green-rly14-result.json and stage-green-rly14.log: Linux stage green.
- /private/tmp/ripdpi-dpi09-smoke-oracle-verified.log: author oracle seven-test pass.
- /private/tmp/ripdpi-dpi09-smoke-verify-commit.log: reviewed staged diff and normal hooks.

## Not verified

No supported Linux full CLI packet capture, Android device/OEM policy,
physical transport, root activation, external server or DPI compatibility run
is claimed. These repairs provide code and local-test evidence (C and bounded
V); they do not establish new full wire conformance S or external compatibility E.

The exact remaining capture check, on Linux with existing capture permission,
is the named cli_packet_smoke_tcp_disoob_family scenario through
scripts/ci/run-cli-packet-smoke.sh with generated scenarios disabled. It must
observe the strict fixture echo and the prefix/urgent/tail sequence and TTL
checks. No capture permission or privilege change was made for this task.
