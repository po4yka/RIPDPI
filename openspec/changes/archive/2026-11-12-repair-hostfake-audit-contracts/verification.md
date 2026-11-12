---
task_id: RST-1794499062183726
change: repair-hostfake-audit-contracts
commit_sha: 72b7b4aa77498be03645ba0e025be3bcba67e8c8
local: passed
local_evidence: Owner regressions, independent review and post-rebase combined gates passed; source input hashes unchanged after validation.
remote_ci: not_applicable
remote_ci_evidence: No hosted dispatch required; push status reported separately.
device: not_applicable
device_evidence: No root, device or external probes authorized.
artifact: not_applicable
artifact_evidence: No release artifact owned.
deployment: not_applicable
deployment_evidence: No deployment owned.
---

# Verification

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-HOSTFAKE-FALLBACK | RST-1794499244045896 | Post-rebase runtime 157 passed, including later-step/suffix preservation and terminal no-op regression; 6856221ff7, 72b7b4aa77 | passed |
| REQ-HOSTFAKE-READINESS | RST-1794499244045896 | UI regression and render, app/service lint and combined staticAnalysis passed; 132c474d09 | passed |
| REQ-HOSTFAKE-MD5 | RST-1794499244635496 | Actual IPv4/IPv6 packet bytes, MD5 option, payload, lengths and independent checksum assertions passed on Linux; 5f299b677a | passed |
| REQ-HOSTFAKE-SEQUENCE | RST-1794499245130397 | Upstream production fix 2aec1e53b0 retained; local boundary tests 3e19f5fd9d and both upstream sequence tests passed on Linux | passed |
| REQ-HOSTFAKE-SPANS | RST-1794499245610183 | Planner/runtime framing and SNI oracle tests passed for TLS 1.2/1.3 and one/two/three records; 59718f2788 | passed |
| REQ-HOSTFAKE-TEMPLATE | RST-1794499246094525 | Config 182, native converter 155 and Kotlin model 69 passed; 9ab8725a13 | passed |
| REQ-HOSTFAKE-RANDOM | RST-1794499246590676 | Full owner batch 1474 passed; final settings/engine follow-up 412 passed; 50d3058d76 | passed |
| REQ-HOSTFAKE-LATE-ERROR | RST-1794499247075214 | Linux restoration/error-context tests and runtime no-replay loopback passed; a3a9782243 | passed |

## Observed evidence

- F1 RED: the receipt expected split and returned HostFake. GREEN: all 149 ripdpi-desync-runtime tests passed, including later-step/suffix preservation and terminal-error loopback byte checks. No raw send was used.
- F5 RED: slash template accepted natively and Unicode accepted by Kotlin. Owner GREEN: 182 config, 155 proxy-config and 69 model tests. Kotlin validates ASCII before case folding, including Kelvin sign rejection.
- UI: app/service lint passed. A configured nonroot HostFake card rendered; the changed prerequisite/fallback text is readable. The UI regression passed. No matching HostFake-specific RDS deck exists; the existing card layout is retained, only state text changes. No screenshot baseline was changed.
- F6: full Kotlin owner batch passed 1474 tests (model74/settings93/data989/engine318), zero failures or skips. After the backup-kind correction, final settings94 and engine318 passed: 412 tests, zero failures or skips.
- Linux raw owner checks passed: actual IPv4/IPv6 packet bytes 1, modulo/oversized-offset 2, and restoration/error context 2. Clippy all-targets with warnings denied passed. These five tests use no raw socket syscalls.
- Final Linux batch on the combined tree passed seven tests: those five plus the two upstream sequence regressions. Linux clippy all-targets with warnings denied passed in 1m 27s. The source bind was read-only, UID/GID nonroot, capabilities dropped, network disabled.
- Final combined Rust batch passed 669 tests, zero failures or ignored tests: config182, desync168 including integration tests, runtime157, proxy-config162 including integration tests. Clippy all-targets with warnings denied passed.
- Combined staticAnalysis and selected UI/theme tests passed in 6m 16s: 93 tests, zero failures/errors/skips. The configured card was rendered and inspected; no golden baseline was changed.
- just ci-gates passed, including locale parity, translation export, cross-language contracts and API snapshots. The host gate selects 13 snapshots and skips Linux-owned ripdpi-runtime-platform on macOS. The separate architecture check reports 18 current/baseline indicators, zero new/worsened/stale entries, 112 crates and 354 internal edges. Locked Cargo metadata resolved 113 workspace members.
- Integration was rebased on origin/main 635c286259f8c9b2dcc943be89059fd1da81c1b7. The DPI-06 receipt condition and counters remain intact. Independent final review approved the conflict resolution and the new terminal no-op test. F3 production code had already arrived through DPI-06, so its additional local commit contains tests only.
- The validated source was committed without further source edits. All 10360 tracked verification input hashes matched the recorded manifest after the runs. Manifest SHA-256: 72a129e93f73ac01daccb767a1064d0b733a4f7dfc7e51d4733b4a32baa5e7e3.
- Independent pr-reviewer source passes cover F1/F4, amended F2/F3/H1, and F5/F6. Parent reviewed the small canonical backup-kind follow-up.
- TCP_REPAIR cleanup was checked against primary Linux sources: [Linux 4.14](https://github.com/torvalds/linux/blob/v4.14/net/ipv4/tcp.c#L2441) and [Linux 6.1](https://github.com/torvalds/linux/blob/v6.1/net/ipv4/tcp.c#L3400). Repair queue reset after repair is off can fail; disabling repair itself is separate and its failure must remain terminal. This is source evidence, not an OS acceptance run.

## Commands and logs

Heavy checks used the normal ripdpi lane first. The owner then authorized one temporary default-lane verification process, limited to four workers; permanent gate settings were not changed. That one process ran final TLS validation, snapshot generation, commit hooks and the combined batches sequentially, then exited.

From native/rust, with the pinned 1.98.1 toolchain and offline dependency resolution:

```sh
cargo test --locked --jobs 4 -p ripdpi-config -p ripdpi-proxy-config -p ripdpi-desync -p ripdpi-desync-runtime
cargo clippy --locked --jobs 4 -p ripdpi-config -p ripdpi-proxy-config -p ripdpi-desync -p ripdpi-desync-runtime --all-targets -- -D warnings
```

From the integration worktree:

```sh
cargo fmt --manifest-path native/rust/Cargo.toml --all -- --check
env -u CARGO_BUILD_JOBS ./gradlew staticAnalysis :app:testGithubFullDebugUnitTest \
  --tests 'com.poyka.ripdpi.ui.screens.settings.DesyncHostFakeSectionTest' \
  --tests 'com.poyka.ripdpi.ui.theme.*' \
  --max-workers=4 -Pripdpi.nativeCpuBudget=4 -Pripdpi.skipNativeBuild=true
just ci-gates
python3 scripts/ci/check_architecture_health.py
cargo metadata --manifest-path native/rust/Cargo.toml --locked --format-version 1
git diff --check
```

The full Kotlin owner batch used :core:data:model:testDebugUnitTest, :core:data:settings:testDebugUnitTest, :core:data:testDebugUnitTest and :core:engine:testDebugUnitTest with the same four-worker/native-skip properties. The final backup follow-up ran the settings and engine suites again.

The isolated Linux batch ran cargo +1.98.1 test -p ripdpi-privileged-ops --locked for the filters build_ordered_tcp_packets_keeps_md5_off_genuine_segments, sequence_arithmetic, tcp_sequence_addition and ordered_send_restoration, then cargo +1.98.1 clippy -p ripdpi-privileged-ops --locked --all-targets --no-deps -- -D warnings. Other privileged syscall tests were not executed.

Logs and runners are local temporary evidence:

- /private/tmp/dpi07-combined-gates.sh and /private/tmp/dpi07-combined-gates.log: final combined batch, exit 0.
- /private/tmp/dpi07-linux-combined.sh and /private/tmp/dpi07-linux-combined.log: final Linux batch, exit 0.
- /private/tmp/dpi07-tls-final-commit.log: final TLS 316-test batch, clippy and normal hooks, exit 0.
- /private/tmp/ripdpi-dpi07-config-logs/f6-kotlin-green.log and f6-backup-green.log: Kotlin owner batches.
- /private/tmp/ripdpi-dpi07-raw-checks/commits.log: three worker commits with normal hooks.
- /private/tmp/dpi07-generate-api.py and /private/tmp/dpi07-api-generate.log: owning API generator, locked metadata, offline public-api and unchanged Cargo.lock. The public-api CLI has no --locked option.

## Evidence boundary

C: F1–F6, readiness wording and conservative H1 no-replay handling are implemented and independently reviewed. V: local unit, loopback, render, serialized packet bytes and source gates passed. S: the repair retains the audit's limited framing/checksum/modulo requirements; it does not establish complete protocol conformance. E: no external peer or DPI compatibility was tested.

## Validation limits

Root activation, physical device, external server/DPI, release and deployment acceptance are not part of this repair. Pure Linux packet tests use nonroot UID, no capabilities and no network. Synthetic TLS fixtures prove framing/SNI invariants, not a complete compliant handshake.

The helper protocol was not expanded. An uncertain helper or post-attempt error remains terminal; this prevents replay but does not provide typed per-phase helper fallback parity. Live TCP_REPAIR state restoration, helper timeouts, Android/VPN protection, retransmission behavior and fake TTL expiry still need the separately authorized acceptance matrix in the original DPI-07 audit. No raw/V/E claim is inferred from the local tests.

Initial Kotlin RED native build was cancelled without test credit. Kotlin unit/lint/staticAnalysis use the repository-supported ripdpi.skipNativeBuild=true CI option; tests themselves are not skipped. The first Linux container attempt failed before tests because the base image lacked cmake; the successful retry used the cached complete image ripdpi-dpi05-linux-check:rust1.98.1. No failed setup is counted as GREEN.
