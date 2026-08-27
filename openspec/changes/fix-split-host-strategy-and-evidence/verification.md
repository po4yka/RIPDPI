---
task_id: DGN-1786885244559735
change: fix-split-host-strategy-and-evidence
commit_sha: 5882155dba25403b80af8048fed2e15d7961385d
local: required
local_evidence: Affected Rust and Kotlin behavior checks, workspace Clippy, API snapshots, architecture checks, and staticAnalysis pass. The full diagnostics suite retains two DNS failures reproduced on base 2c8b471ef; the native hotspot gate retains the same baseline listener overage.
remote_ci: required
remote_ci_evidence: Pending exact-SHA hosted checks after the authorized push; historical runs are not credited to this revision.
device: required
device_evidence: Pending permission to install the current APK and run physical proxy/VPN, Wi-Fi/cellular, and handover scenarios. No current device proof is claimed.
artifact: required
artifact_evidence: Current githubFullDebug arm64 APK assembly and artifact verification are in progress.
deployment: not_applicable
deployment_evidence: No production deployment or release publication is owned by this change.
---

# Verification — 2026-08-27

The code fixes are implemented and reviewed. Portfolio acceptance remains open:
physical-device coverage and exact-SHA hosted results are separate from source,
loopback packet, and artifact checks. Historical APK, CI, and Pixel 7 results
from earlier revisions do not establish these fixes.

## Implemented behavior

- Planned TCP steps retain their source send-step index before activation or
  offset filtering. Execution flags, effective family, marker, and resolved
  offset now refer to that same step, including sorted MultiDisorder boundaries.
- Generic and special execution errors preserve the plan and actual partial
  action/write/await/byte counts. TLS-prelude failure metadata remains coherent.
- Fully filtered plans still send HTTP-modified payloads, but report
  `ActivationSkipped`. A prelude-only execution reports `TlsRecord`.
- Allocated connection ordinals without accepted terminal receipts make the
  bounded evidence snapshot incomplete, including a missing last receipt.
- IN_PATH leases bind the authenticated listener to the actual TUN generation
  and verified callback revision. Refresh revokes the old lease before rebuild;
  route loss and restoration cannot revive an old issued lease.
- Route authority is sampled before native start and throughout polling, then
  frozen at terminal report acceptance before persistence can suspend. Missing
  or lost ownership cannot credit the active strategy.
- The CLI packet oracle checks exact HTTP bytes, one TCP stream, sequence
  coverage, and a packet boundary after the first Host byte. Coalesced,
  conflicting, incomplete, and malformed captures fail.

Source API changes are intentional: `PlannedStep` requires
`source_send_step_index`; the non-serialized Kotlin lease requires
`issuedRevision`. All repository callers were updated. No wire/schema version,
locale, dependency, golden fixture, or quality baseline was changed.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-STRATEGY-EVIDENCE-001 | DGN-1786885745283306 | Candidate/config and exact-plan tests in the complete Linux monitor/runtime suite | PASS |
| REQ-STRATEGY-EVIDENCE-002 | RST-1786885745241507 | Applied, activation-skipped, fallback, partial-write, and typed failure tests; production authenticated SOCKS test | PASS |
| REQ-STRATEGY-EVIDENCE-003 | RST-1786885745241507 | Actual action counters and exact Linux HTTP PCAP; physical and TLS PCAP remain pending | PARTIAL |
| REQ-STRATEGY-EVIDENCE-004 | RST-1786885745241507 | Generation/terminal/late-receipt coverage and rejected trailing receipt regression | PASS |
| REQ-STRATEGY-EVIDENCE-005 | DGN-1786885745283306 | Canonical candidate and effective marker/family matching tests; source-index regressions | PASS |
| REQ-STRATEGY-EVIDENCE-006 | DGN-1786885745300444 | Existing archive/golden/privacy tests pass within diagnostics; fixtures unchanged and no blessing used | PASS |
| REQ-STRATEGY-VERDICT-001 | DGN-1786885745300444 | Baseline-current evaluator, terminal ownership and persistence regressions | PASS |
| REQ-STRATEGY-VERDICT-002 | DGN-1786885745300444 | RAW/IN_PATH separation, generation/revision leases, refresh invalidation, and terminal authority tests | PASS |
| REQ-STRATEGY-VERDICT-003 | DGN-1786885745300444 | Missing terminal capture, partial/error receipts, fallback, cancellation, and route-loss tests | PASS |
| REQ-STRATEGY-VERDICT-004 | DGN-1786885745300444 | Native endpoint/stage projections pass; two unrelated DNS planner tests remain baseline failures | PARTIAL |
| REQ-STRATEGY-VERDICT-005 | DGN-1786885745300444 | App unit tests and staticAnalysis pass; UI/locale/archive schemas unchanged | PASS |

## Observed checks

All heavy local commands used the machine-wide build gate and at most two
Cargo jobs / one Gradle worker.

- Serial `cargo test --locked -j2 -p ripdpi-desync-runtime -p ripdpi-desync
  -p ripdpi-runtime-api`: 310 tests passed, no failures or ignored tests.
  This includes the TLS failure and prelude-only review regressions.
- `cargo clippy --locked --workspace --all-targets --jobs 2 -- -D warnings`
  and `cargo fmt --all -- --check`: PASS, including commit hooks.
- Canonical API snapshot generation changed one public field; unblessed
  `check_rust_api_snapshots.py`: PASS for this host. The runtime-platform
  snapshot is explicitly Linux-owned and skipped on macOS.
- Service: 1860/1860; runtime-state: 181/181; app: 1765/1765.
  Diagnostics: 1406/1408 passed. Both remaining DNS planner failures reproduce
  on detached base `2c8b471ef` (expected resolver count 16, actual 12).
- Final `:core:service:detekt :core:service:ktlintCheck
  :core:service:testDebugUnitTest staticAnalysis`: PASS.
- Architecture health: zero new/worsened indicators. Native architecture
  contracts: zero violations. Locked Cargo metadata, task validation, strict
  OpenSpec validation, and staged whitespace checks: PASS.
- Native hotspot check remains blocked by the unchanged
  `ripdpi-tunnel-core/src/io_loop/tcp_accept/listener.rs`: 72 lines, limit 54.
  The identical failure was observed on base `2c8b471ef`; no baseline was raised.
- Read-only native and Kotlin reviews: no remaining actionable findings after
  fixing their regressions. Published Markdown legal review is recorded
  separately from software validation.

The initial simultaneous Cargo test/Clippy run hit an E0463 doctest metadata
error; the serial full rerun passed. One Linux full-suite repetition timed out
in the existing QUIC echo test; no timeout or assertion was changed. Its final
full-suite rerun status is recorded below, rather than discarded.

## Linux execution and packet evidence

The final seven-package production runtime/monitor suite passed: 794 tests,
zero failures, and nine pre-existing ignored tests. The QUIC echo test also
passed in this complete rerun. Both final Docker runs exited 0 without OOM.
The full log is `ripdpi-split-host-linux-native-confirmed-20260827.log`.

`run-cli-packet-smoke.sh` on Linux with scenario
`cli_packet_smoke_tcp_split_family`: PASS (one executed scenario, zero ignored).
Independent tshark inspection of the final PCAP found one stream with a
28-byte prefix ending in `Host: f` and the following 34-byte segment starting
with `ixture.test`. This proves the exact HTTP boundary for the controlled
loopback fixture; it does not prove TLS segmentation or physical Android paths.

Final PCAP SHA-256:
`e66c21fd584f182daad486c9104b8973d81e36d43347e778819acb3489d162d3`.

Local evidence directory:
`/private/tmp/ripdpi-split-host-evidence-20260827/linux-packet-final/cli_packet_smoke_tcp_split_family/`.

Selected local logs under `/private/tmp/`:

- `ripdpi-split-host-native-final-serial-green-20260827.log`
- `ripdpi-split-host-api-final-check-20260827.log`
- `ripdpi-split-host-kotlin-gates-final-20260827.log`
- `ripdpi-split-host-baseline-dns-20260827.log`
- `ripdpi-split-host-prelude-review-red-20260827.log`
- `ripdpi-split-host-linux-packet-final-20260827.log`

## Remaining acceptance

Keep `TST-1786885745317178` unchecked until the required physical RAW_PATH /
owned IN_PATH matrix, current artifact, and hosted checks are resolved. Device
coverage includes available Wi-Fi/cellular, IPv4/IPv6, concurrent HTTP/HTTPS,
QUIC-success with HTTPS-failure, cancellation, and handover. No installation or
network-state changes have been performed without permission.
