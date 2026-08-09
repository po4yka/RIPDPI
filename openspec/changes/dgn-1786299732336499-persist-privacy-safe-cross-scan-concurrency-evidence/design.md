## Context

Current diagnostics model TLS fingerprint and same-authority concurrency as
independent axes and can confirm replicated evidence within one scan. Stable
catalog aliases exist, while raw target/network facts are prohibited from persistence.

## Goals / Non-Goals

- Goal: enable the bounded two-scan confirmation path.
- Goal: preserve diagnostics privacy, cancellation, retention, and reset contracts.
- Non-goal: retain raw targets or turn one scan into an actionable verdict.

## Decisions

- Persist one bounded record per hashed scope, evidence axis pair, and stable
  catalog alias with categorical outcomes and timestamps only.
- Require different aliases, eligibility, freshness, and matching schema/scope
  before cross-scan confirmation.
- Treat partial, cancelled, migrated-unknown, and expired records as non-actionable.
- Project the bounded record through backup/archive only if privacy and
  completeness contracts remain exact; otherwise omit with an explicit reason.

## Contracts and ownership

- Diagnostics Rust owns assessment invariants and wire types.
- Data owns Room schema, migration, retention, reset, and backup behavior.
- Kotlin diagnostics owns UI/export projection with no raw identifiers.
- Database schema and archive fixtures are serialized lanes.

## Risks / Trade-offs

- Stable aliases can drift with catalogs; version them and invalidate unknown mappings.
- Persistence can create correlation risk; bound retention and use hashed scope only.
- Restored stale data can create false confirmation; validate freshness and schema on restore.

## Migration Plan

Add optional schema and migration with empty history as the legacy default, then
enable assessment consumption after persistence and lifecycle tests pass.
Rollback stops writing and ignores the optional history table safely.
