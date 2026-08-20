---
task_id: DGN-1786885244559735
change: fix-split-host-strategy-and-evidence
commit_sha: 04b2419a5
local: required
local_evidence: Combined Rust, Kotlin, contract, privacy, architecture, lint, harness, API-snapshot, and task gates pass on the rebased code commit.
remote_ci: required
remote_ci_evidence: Pending push and hosted workflow completion for the final SHA.
device: required
device_evidence: Pixel 7 arm64 API 37 ran proxySplitHostPlusOneRoutesTlsTraffic successfully against the local TLS fixture; exact on-wire PCAP and cellular/handover coverage remain unavailable on this locked no-SIM device.
artifact: required
artifact_evidence: githubFullDebug arm64 APK assembled with native assets; SHA-256 ee6e9b9d99e484d057d7cde146904e82c6d4ecc6a1a51132c387eac9e715ec69, valid Android debug v2 signature, and arm64 ELF verification passed.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

The implementation, local source-level gates, governed API and archive
snapshots, arm64 artifact, and physical-device application-path smoke are
complete. Hosted CI, raw-PCAP proof of the exact TCP split boundary, and the
full Wi-Fi/cellular/handover device matrix remain distinct pending acceptance
layers and are not upgraded from missing evidence to PASS.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-STRATEGY-EVIDENCE-001 | DGN-1786885745283306 | Rust candidate/config, exact-plan, marker, TLS-prelude, TCP/UDP receipt, and promotion tests passed in the affected nine-package suite | PASS |
| REQ-STRATEGY-EVIDENCE-002 | RST-1786885745241507 | Typed applied/skipped/plain-fallback/execution/runtime-failure receipt tests passed; UDP production proxy E2E ran on macOS | PASS |
| REQ-STRATEGY-EVIDENCE-003 | RST-1786885745241507 | Bounded action/write/await/byte counter tests and exact PCAP reconstruction harness tests passed; physical arm64 split(host+1) TLS round-trip passed, while exact on-wire PCAP remains unavailable on the locked device | PARTIAL |
| REQ-STRATEGY-EVIDENCE-004 | RST-1786885745241507 | Generation, terminal-status, late-receipt, panic, cancellation, and worker-join tests passed | PASS |
| REQ-STRATEGY-EVIDENCE-005 | DGN-1786885745283306 | Canonical candidate isolation and effective route-feature matching tests passed | PASS |
| REQ-STRATEGY-EVIDENCE-006 | DGN-1786885745300444 | Authorized schema-11 golden family reviewed; unblessed owner tests and hostile whole-ZIP privacy scans passed | PASS |
| REQ-STRATEGY-VERDICT-001 | DGN-1786885745300444 | Candidate-scoped current-strategy evaluator and exact snapshot/plan mismatch tests passed | PASS |
| REQ-STRATEGY-VERDICT-002 | DGN-1786885745300444 | RAW candidate and authenticated active-service path axes, ownership, aggregation, persistence, and archive tests passed | PASS |
| REQ-STRATEGY-VERDICT-003 | DGN-1786885745300444 | Partial, deadline, zero-attempt, launch, fallback, terminal, malformed-count, and cancellation tests passed | PASS |
| REQ-STRATEGY-VERDICT-004 | DGN-1786885745300444 | DNS, TCP, TLS, HTTP/error-page, QUIC, route, and response-stage projections passed their Rust/Kotlin tests | PASS |
| REQ-STRATEGY-VERDICT-005 | DGN-1786885745300444 | Nine-locale diagnostics wording, UI tone, session-scoped archive summary, and app unit tests passed | PASS |

## Required acceptance evidence

- Local: all named Rust, Kotlin, contract, privacy, architecture, and task gates
  in `tasks.md` at one exact commit SHA.
- Remote CI: required workflows green for the same SHA; local PASS is not a
  substitute.
- Device: owned-route-correlated RAW_PATH and active-service IN_PATH matrix on a
  supported physical device, with network handover and concurrent lanes.
- Artifact: assembled debug artifact identity, hash, signature, and native ABI
  verification for the tested SHA.
- Deployment: not applicable; this change does not authorize publication or
  production rollout.

## Observed local commands

- `cargo test --locked` for the nine affected Rust packages: PASS.
- `cargo clippy --locked --workspace --all-targets -- -D warnings`: PASS.
- `cargo fmt --all -- --check`, `cargo metadata --locked`, `cargo deny --locked
  check`, unsafe-boundary and architecture-health checks: PASS.
- Full `:core:diagnostics:testDebugUnitTest` and
  `:core:service:testDebugUnitTest` with native build skipped: PASS.
- Targeted app diagnostics/ViewModel tests and app/service Android lint: PASS.
- Phase-16 PCAP reconstruction tests and Android packet-smoke shell harness:
  PASS, including fail-closed zero-executed-scenario coverage.
- Harness manifest/link/policy/Cargo-lock/skill/rule drift suite: PASS.
- Canonical `ripdpi-desync` API snapshot update and the final unblessed
  `check_rust_api_snapshots.py` run: PASS; the approved snapshot contains only
  the typed TLS-prelude surface.
- `assembleGithubFullDebug` with `arm64-v8a` native assets: PASS. The APK
  signature, package, native ABI/ELF metadata, and root-helper packaging were
  verified before installation.
- Pixel 7 arm64 API 37 instrumentation:
  `proxySplitHostPlusOneRoutesTlsTraffic` PASS (1/1) against the repository TLS
  fixture. `adbd` cannot run as root on the production build and no on-device
  `tcpdump` is available, so this proves the Android service/TLS path but not
  the precise packet boundary.
