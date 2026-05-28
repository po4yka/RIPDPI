---
title: Validate H3-to-H2 MASQUE fallback telemetry sufficiency
type: task
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-15
updated: 2026-05-29
---

- [ ] #task Validate H3-to-H2 MASQUE fallback telemetry sufficiency #repo/RIPDPI #area/rust-native #status/backlog 🔼

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
