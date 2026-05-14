---
title: Extend contract_fixtures with connectivity scan-report golden covering cancellation + partial-results
type: task
status: doing
area: testing
priority: high
owner: Test Automation Engineer
parent: null
blocks: []
blocked_by: []
created: 2026-05-04
updated: 2026-05-04
---

- [ ] #task Extend contract_fixtures with connectivity scan-report golden covering cancellation + partial-results #repo/RIPDPI #area/testing #status/doing ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `extend-contract-fixtures-with-connectivity-scan-report-golden-covering`
- **Verify:** `cargo nextest run -p ripdpi-monitor-engine --test contract_fixtures`
- **Scope (only modify these + this file + the ledger):** `native/rust/crates/ripdpi-monitor-engine/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Objective
Extend `native/rust/crates/ripdpi-monitor-engine/tests/contract_fixtures.rs` with a golden test that exercises a connectivity scan running through the parallel `Dns`/`Tcp`/`Quic` group, cancels mid-`Quic`, and asserts the resulting `ScanReport` JSON shape is byte-identical to a checked-in fixture. This is gate G6 of POY-12.

## Context
The connectivity-runner decomposition routes every per-stage runner through the new `support::collect_family_steps` helper plus a single `EnvironmentRunner` short-circuit. There is currently no end-to-end fixture that locks in the post-decomposition `ScanReport` for the cancellation/partial-results path. A regression in stage ordering, partial-stage emission, or `connectivity_summary` aggregation would not be caught by per-runner unit tests alone.

## Acceptance criteria
- Add a `#[test]` in `tests/contract_fixtures.rs` (or a new `tests/connectivity_report_golden.rs`) that:
1. Builds an `ExecutionPlan` with bundled-fixture `dns_targets`, `tcp_targets`, `quic_targets`, `domain_targets`, and a `network_snapshot` with `transport == "wifi"` and `validated == true` (so `EnvironmentRunner` does not abort).
2. Runs the connectivity stage pipeline with a cancel token flipped after the first QUIC target.
3. Serialises the resulting `ScanReport` to JSON and compares against a checked-in fixture (e.g., `tests/fixtures/connectivity_report_partial.json`).
- Fixture is committed alongside the test.
- Test deliberately uses no live network — all probes go through the bundled-fixture transport (see `direct_transport()` in `engine/runtime_tests.rs` for the existing pattern, swapping for a fixture transport if required).
- Output JSON is stable across runs (no timestamps, ordering, or RNG seeds in the asserted shape — strip or freeze them in the test).

## Required verification
- `cargo nextest run -p ripdpi-monitor-engine --test contract_fixtures` green.
- Mutating the connectivity-stage ordering or partial-results flush logic must make the test fail.

## Risks
- Time-stamp / ordering instability if not stripped → schema-only check or fixed-clock test transport required.

## Definition of done
- Test + fixture committed, green locally and in CI.
- POY-12 gate G6 marked satisfied.
