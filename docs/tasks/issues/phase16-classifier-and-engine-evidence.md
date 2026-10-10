---
id: TST-1791631798720508
title: Separate Phase16 classifier tests from engine packet evidence
kind: bug
status: doing
area: testing
priority: high
owner: Phase16 integration maintainer
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-10
updated: 2026-10-10
spec_reason: tooling-only
---

## Goal

Separate the synthetic L7 classifier self-test from current-engine packet
evidence. Preserve the 23 expected blocked control cells. Do not report fixture
replay or incomplete captures as release acceptance. Runtime network behavior,
native dependencies, and existing golden fixtures stay unchanged.

## Acceptance criteria

- The actual 63-cell fixture replay passes its complete expected verdict table.
  Changed verdicts in either direction, duplicate or missing cells, unknown
  verdicts, wrong versions or mode, bad totals, and missing evidence fail.
- A distinct Linux producer runs the current CLI with strict TLS handshake and
  response checks, captures the upstream packets, and records source, binary,
  profile, fixture, and capture provenance. A no-desync control must be detected.
- The engine gate validates the complete declared TCP SNI coverage using raw
  per-segment parsing. Missing, stale, synthetic, incomplete, unsupported, or
  blocked engine evidence cannot pass. QUIC, MTU, blackhole, and carrier-level
  coverage are not inferred from this bounded TLS profile.
- Phase16 receipts and CI distinguish classifier health from engine acceptance.
  Combined tests, strict packet execution, lint, review, and task validation
  pass, or their exact blocker is recorded. Integrate by fast-forward into main
  and push to origin as authorized on 2026-10-10.

## Parallel ownership

| Owner | Paths and responsibility |
| --- | --- |
| Classifier and Phase16 worker | `runner/expectations.py`, the new TSPU expected-verdict data, `tests/test_replay.py`, classifier validation tests, `scripts/ci/run-tspu-dryrun.sh`, `run-phase16-matrix-entry.sh`, `phase16_pcap_summary.py`, `phase16_matrix.py`, `scripts/tests/test_phase16_matrix.py`, and `contract-fixtures/phase16_lab_matrix.json` |
| Engine evidence worker | New `runner/engine_evidence.py` and its tests, `scripts/ci/run-l7-engine-evidence.sh`, `native/rust/crates/ripdpi-cli/tests/packet_smoke.rs`, and `scripts/ci/packet-smoke-scenarios.json` |
| Integration owner | This task and its execution record, generated board, workflows, contributor documentation, combined validation, review, main integration, and push |

Each writer uses a separate worktree. The Phase16 matrix and packet-smoke
registry each have one writer. Test evidence contracts are tooling-only; no
production JNI, protobuf, storage, or protocol contract is changed. Keep the
synthetic row ID compatible. The new engine row is opt-in and must report its
bounded applicable coverage explicitly.
