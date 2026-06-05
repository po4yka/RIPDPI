---
title: Validate H3-to-H2 MASQUE fallback telemetry sufficiency
type: task
status: doing
area: rust-native
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-06-05
---

## Summary

`native/rust/crates/ripdpi-masque/CONFORMANCE.md` flags continued verification that HTTP/3 to HTTP/2 fallback telemetry is sufficient for rollout decisions as remaining work. Define the telemetry contract and add tests asserting that every distinct fallback-trigger reason is captured.

## Context

The existing test `quic_migration_snapshot_records_http2_fallback_reason` covers one case. Rollout decisions need to distinguish at least: handshake failure, post-handshake idle, transport error, server-side rejection, and explicit downgrade.

## Acceptance criteria

- [x] (partial, 2026-05-15; refreshed 2026-05-28) An enum (or string vocabulary) enumerates fallback-trigger reasons. `native/rust/crates/ripdpi-masque/src/migration.rs` defines `MigrationStatus` and `H3FallbackReason` helpers that render the documented stable strings; `record_quic_migration_status` still accepts strings for backwards compatibility while callsites migrate.
- [ ] Each reason has a dedicated unit test asserting the snapshot captures it. **DEFERRED:** the existing `quic_migration_snapshot_records_http2_fallback_reason` test covers one pair; per-reason coverage plus callsite migration keeps the test set exhaustive.
- [x] (2026-05-15; moved 2026-05-29) The telemetry export schema is documented in `native/rust/crates/ripdpi-masque/CONFORMANCE.md`.

## Definition of done

- A new fallback reason cannot be added in the future without also adding a test, by virtue of the enum match being non-exhaustive in the assertion helper.

## Links

- `native/rust/crates/ripdpi-masque/CONFORMANCE.md`

## Work log

- 2026-06-05: AC1 fully done — `migration.rs` defines `MigrationStatus` and `H3FallbackReason` with stable strings and backwards-compatible string API. AC3 done — CONFORMANCE.md documents the telemetry vocabulary. AC2 still open: `migration.rs` typed_status_tests (lines 97–131) only verify string rendering; no per-reason snapshot-capture test exists beyond the single `quic_migration_snapshot_records_http2_fallback_reason` in `tests.rs`. DoD (non-exhaustive enum match assertion helper) also not yet implemented. Callsite migration from string API to typed enums is pending.
- 2026-06-05 (re-audit): Confirmed AC1 and AC3 [x] via source read (`migration.rs` lines 1–132, `CONFORMANCE.md` §"QUIC Migration Telemetry Vocabulary"). AC2 [ ] confirmed — `rg H3FallbackReason tests.rs` returns nothing, CONFORMANCE.md line 16 explicitly states "five tests in migration.rs cover local telemetry string stability only." Status corrected from `backlog` to `doing` (two of three criteria verifiably done, one still open).
