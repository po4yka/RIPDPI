---
task_id: ENG-1794501890187395
change: repair-dpi04-fake-send-contracts
commit_sha: 4ea4815652e545c19de2fc4a1c25d0fdaf8f8fd8
local: passed
local_evidence: Owning DPI-04 regressions and affected-crate Linux Clippy passed. The full privileged suite remains failed with three BPF EPERM outcomes outside this component; exact results and limits are recorded below.
remote_ci: not_applicable
remote_ci_evidence: Hosted CI is not the acceptance gate for this local repair; a main push is authorized and reported separately.
device: not_applicable
device_evidence: No physical device, emulator, root activation, or external-server probe was run. Android wire acceptance remains unverified.
artifact: passed
artifact_evidence: Eight separate finding commits, two independent review passes after integration, unchanged-input Linux receipts, and this evidence record exist.
deployment: not_applicable
deployment_evidence: No release or deployment is owned by this change.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-FAKE-ACTIVATION | ENG-1794502313284304 | Commit 4febc093e7; real TCP peer receives the original 100 bytes when an explicit round filter or the default first-round gate skips round 2; explicit round 2 remains available | Passed local peer regression |
| REQ-FAKE-CLEANUP | ENG-1794502315444353 | Commit e1b25b59c2; actual vmsplice/splice error injection, peer byte equality, normal TTL restored, known progress retained | Passed Linux regression |
| REQ-FAKE-REPLAY | ENG-1794502317963326 | Commit 4d841bda2a; opaque fake/ordered uncertainty is terminal; known 7+3 byte progress is retained; actual partial HostFake/FakeDisorder peer receives one prefix | Passed local regression and review |
| REQ-FAKE-GENUINE | ENG-1794502321010120 | Commit ec34385184; actual common emitter builder decoded as IPv4/IPv6; genuine TTL is normal, MD5 absent; explicit first FakeDisorder TTL remains 1; independent checksum checks | Passed serialization regression; privileged wire unverified |
| REQ-FAKE-INPUT | ENG-1794502324054173 | Commit 8313d1b995; malformed Unicode escape uses checked slicing; CLI and parser regressions preserve escape behavior | Passed config suite |
| REQ-FAKE-SEQUENCE | ENG-1794502326974327 | Commit c54485ff48 adds decoded packet boundary evidence; incoming main already contains the same wrapping-add root fix from DPI-06 | Passed modulo and packet regression |
| REQ-FAKE-HELPER | ENG-1794502329017041 | Commit 41387d94f6; real UnixStream/SCM_RIGHTS payload equality, bounded admission and legacy refusal, advertised limits even for small requests, bounded progress replies | Passed helper/protocol/platform suites |
| REQ-FAKE-IPID | ENG-1794502330712741 | Commit d9c7e044b6; explicit IPv4 zero IDs fail before repair/emission; IPv6 remains available | Passed pre-emission regression; zero-ID backend explicitly unsupported |

## Exact source and integration

The audit started at `69502620c30cf97528158a37f12b7e2724f527c8`.
The final code was rebased onto `475698737cfc64db16c31c20af06961ec0651ada`.
DPI-05, DPI-06, and HostFake fixes that arrived during the work are retained.
The final tested code SHA is `4ea4815652e545c19de2fc4a1c25d0fdaf8f8fd8`.
Its `native/rust` tree is `e417769e07d21fa137854e13b85e8127f7d11066`.

The earlier final privileged and relay runs used
`81034d11ae84be1ba92828369cc1e816156bab6a`, with Rust tree
`72d2860294fdfe1c2c17803157a33fba31f18b8e`.
The only subsequent Rust changes are the missing optional preflight argument
in a helper progress test and supported failure errno in the MD5 audit test.
No privileged or relay owning input changed, so those checks were not repeated.

Independent `pr-reviewer` and `unsafe-code-auditor` passes found no remaining
actionable issue in the reviewed repair. A final focused review checked the
HostFake merge, the single ordered builder, receipts, progress, and cleanup.
Review is source evidence and does not replace an observed run.

## Observed commands and results

All Linux commands used the pinned Rust 1.98.1 toolchain, `--offline --locked`,
`--jobs 4`, and the authorized `build-gate --lane rly14`.
Docker ran as UID 65534 with all capabilities removed, network disabled,
read-only source, no-new-privileges, and a writable disposable target.
Debug assertions and overflow checks remained enabled; debug symbols and
incremental compilation were disabled to limit disk use.

```sh
cargo test --offline --locked --jobs 4 -p ripdpi-privileged-ops
cargo test --offline --locked --jobs 4 --no-fail-fast \
  -p ripdpi-config -p ripdpi-desync-runtime \
  -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-root-helper-protocol \
  -p ripdpi-root-helper -p ripdpi-runtime-platform
cargo test --offline --locked --jobs 4 -p ripdpi-proxy-runtime --lib runtime::relay::
cargo clippy --offline --locked --jobs 4 \
  -p ripdpi-privileged-ops -p ripdpi-config -p ripdpi-desync-runtime \
  -p ripdpi-proxy-runtime-desync-adapter -p ripdpi-root-helper-protocol \
  -p ripdpi-root-helper -p ripdpi-runtime-platform -p ripdpi-proxy-runtime \
  --all-targets --no-deps -- -D warnings
cargo metadata --offline --locked --format-version 1
cargo fmt --all -- --check
python3 scripts/ci/check_architecture_health.py --check
python3 scripts/ci/check_unsafe_boundaries.py
just ci-gates
```

| Check | Observed result |
|---|---|
| Config | 184 passed |
| Desync runtime | 155 passed |
| Desync adapter | 36 library and 10 audit integration tests passed |
| Root helper | 67 passed |
| Helper protocol | 42 unit, 7 bulk, 3 deadline, 3 SCM_RIGHTS tests passed |
| Runtime platform | 53 passed |
| Contracts batch total | 560 passed, 0 failed, 0 ignored; exit 0 |
| Privileged ops full suite | 93 passed, 3 failed, 0 ignored; exit 101 |
| Proxy relay scope | 48 passed; 347 unrelated library tests filtered by scope; exit 0 |
| Affected eight-crate Linux Clippy | All targets, warnings denied; exit 0 |
| Formatting, locked metadata, architecture, unsafe boundary gate | Exit 0 |
| `just ci-gates` | Exit 0; no baseline changes; Linux-owned API snapshot is skipped on Darwin by the existing gate |

The three full-suite failures are:

- `bpf_drop_sack_filter_attaches_and_detaches_cleanly`;
- `bpf_strip_timestamps_filter_attaches_and_detaches_cleanly`;
- `bpf_filter_detach_removes_program`.

Each receives `SO_ATTACH_FILTER -> EPERM` in this restricted environment.
The exact kernel/container policy is not established. The full suite is not
reported as green. No test was disabled and no extra capability was granted.
These BPF cases are outside the DPI-04 component acceptance scope.
Five existing raw/mark tests can return early without capabilities; their
harness success does not prove privileged packet transmission.

Earlier failed attempts are retained: the first merged Linux batch failed
with E0061 in `transport/progress_tests.rs` after preflight gained an argument.
The argument was added and the full contracts batch plus Clippy then passed.
The MD5 test initially rejected actual ENOPROTOOPT; it now accepts explicit
unsupported outcomes while still requiring a failing syscall. Initial helper
fixture EACCES errors came from noexec TMPDIR and were resolved by an executable
disposable test directory, without changing container capabilities.

## Local evidence files

Logs are in `build/reports/dpi04/` in the repair worktree:

- `linux-final-81034d11ae84-20261112T184659Z-privileged.log`;
- `linux-final-81034d11ae84-20261112T184659Z-relay.log`;
- `linux-final-4ea4815652e5-20261112T184949Z-contracts.log`;
- `linux-final-4ea4815652e5-20261112T184949Z-clippy.log`;
- `ci-gates-4ea48156.log`, `metadata-final.json`, `architecture-final.log`,
  and `unsafe-final.log`.

The Linux runner records exact HEAD, native tree, command, exit code, and
unchanged inputs before and after each run. Logs and disposable build outputs
are local artifacts, not committed source or baselines.

## Proof boundary and remaining checks

- C: all eight confirmed findings have source changes or retained upstream
  fixes, owning regression evidence, and independent review.
- V: observed local peer, Linux shared-page cleanup, packet serialization,
  and real UnixStream/SCM_RIGHTS behavior. Packet builders are not wire capture.
- S: the original audit's checked specification and internal-contract evidence
  remains the requirement baseline. This repair adds no new external standard claim.
- E: Android and external-server compatibility remain unverified.

No root helper activation, TCP_REPAIR/raw send acceptance on a physical device,
real secrets, external-server traffic, release, or deployment was performed.
The next authorized platform check must capture the actual fake/real packets
and server byte stream for default fake, MD5 unavailable/available,
secondary/timestamp, seqgroup, custom order, and faults after send. It must
verify TTL/MD5/sequence/ID fields with an independent packet oracle, normal TTL
restoration, no duplicate real bytes, and no replay after possible commitment.
A permitted Linux BPF environment must rerun the three named full-suite failures.
Exact-zero IPv4 ID requires a different capable backend if future scope demands it.
