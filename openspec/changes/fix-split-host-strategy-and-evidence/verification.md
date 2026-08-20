---
task_id: DGN-1786885244559735
change: fix-split-host-strategy-and-evidence
commit_sha: 7b6f436c7499e33bc725fa47e05fa3a189019af9
local: required
local_evidence: Implementation and local source gates pass; ripdpi-desync API snapshot blessing remains pending, and the scripted snapshot checker is currently blocked by the local cargo guard.
remote_ci: required
remote_ci_evidence: Pending push and hosted workflow completion for the final SHA.
device: required
device_evidence: Pixel 7 arm64 API 37 connected; current-branch APK was not installed because artifact assembly exhausted host disk space.
artifact: required
artifact_evidence: assembleGithubFullDebug arm64-v8a failed with ENOSPC during native compilation/linking; no artifact result is credited.
deployment: not_applicable
deployment_evidence: No deployment is owned by this change.
---

# Verification

The implementation and local source-level gates are complete except for the
separately governed `ripdpi-desync` API snapshot. The direct snapshot diff
confirms that unapproved public-surface drift; the full scripted checker is
currently blocked by the local cargo guard before it can report a complete
current run. Hosted CI, an assembled APK, and physical-device path evidence
remain distinct pending acceptance layers.

## Requirement evidence

| Requirement | Execution step | Evidence | Result |
|---|---|---|---|
| REQ-STRATEGY-EVIDENCE-001 | DGN-1786885745283306 | Rust candidate/config, exact-plan, marker, TLS-prelude, TCP/UDP receipt, and promotion tests passed in the affected nine-package suite | PASS |
| REQ-STRATEGY-EVIDENCE-002 | RST-1786885745241507 | Typed applied/skipped/plain-fallback/execution/runtime-failure receipt tests passed; UDP production proxy E2E ran on macOS | PASS |
| REQ-STRATEGY-EVIDENCE-003 | RST-1786885745241507 | Bounded action/write/await/byte counter tests and exact PCAP reconstruction harness tests passed; authenticated TCP proxy E2E remains Linux/Android-only | PARTIAL |
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
- Direct `cargo-public-api` check for `ripdpi-desync`: FAIL only for the
  unblessed public TLS-prelude surface. Full scripted Rust API snapshot checker:
  BLOCKED in this shell by local cargo guard rejecting `cargo public-api`.
