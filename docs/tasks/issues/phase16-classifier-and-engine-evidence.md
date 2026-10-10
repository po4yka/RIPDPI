---
id: TST-1791631798720508
title: Separate Phase16 classifier tests from engine packet evidence
kind: bug
status: done
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
closed_at: "2026-10-10T11:48:14Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: "Implemented and pushed origin/main e1ceedf2e14a6b8188684084341b93a3560d88f2; remote SHA verified. Combined Python checks: 73 TSPU tests and 46 script tests pass. Actual Phase16 classifier row passes all 63 expected cells, including 23 blocked controls and 40 bypassed results; releaseAcceptance=false. Actual Linux Phase16 engine row passes two dedicated TLS tests with 1 passed and 0 ignored each; raw PCAP is redecoded and checked against current source and binary hashes. Capture source e1ceedf2e14a6b8188684084341b93a3560d88f2; binary SHA256 284f4c16398a62022862c743b5a8ade59c5a9be3744dabeffb1259e7f73287ed. Raw capture hashes: control 633cd923c8f3f4d52e6602f5135773bab65350a46c63c2617d05855b1ee39781; candidate 7be378bdc8290db0ec604047fbf91c54de4b6cb387f5cc8017bdcadcbdaa072a. Scope: TCP -s 3 profile against RST-on-SNI, SNI replacement and their combination in a per-segment Linux loopback model. Full matrix, QUIC, MTU, blackholes, censor reassembly, Android and carrier acceptance remain unverified. Capture validation repeats successfully after fetch/rebase. Architecture health has zero new or worsened indicators; locked Cargo metadata, native compile, native Clippy, formatting, matrix validation, actionlint, task contracts and normal commit hooks pass. Independent reviewer fixes were verified; structured autoreview returned no actionable P0 findings. GitHub CI and L7 workflow for the pushed SHA are in progress; no remote CI success is claimed."
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
