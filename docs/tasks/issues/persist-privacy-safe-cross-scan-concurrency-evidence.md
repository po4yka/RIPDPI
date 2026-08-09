---
id: DGN-1786299732336499
title: Persist privacy-safe cross-scan concurrency evidence
kind: feature
status: backlog
area: diagnostics
priority: high
risk: high
owner: Diagnostics evidence
parent: null
blocked_by: []
spec_mode: required
openspec_change: dgn-1786299732336499-persist-privacy-safe-cross-scan-concurrency-evidence
created: 2026-08-09
updated: 2026-08-09
related_tasks: []
---

## Goal

Complete the second confirmation path for the existing independent
fingerprint/concurrency diagnostic by retaining bounded, privacy-safe evidence
across scans without storing raw targets, SNI values, or network identifiers.

## Ownership

- Primary surfaces: diagnostics observation/assessment contracts, Room/archive
  persistence, retention, reset, Kotlin projections, and focused tests.
- Serialized lanes: diagnostics database schema, archive fixtures, wire schemas,
  and locale sets have one writer at a time.

## Acceptance criteria

- Two eligible scans can confirm the conjunction only when their evidence is
  independent, fresh, scope-consistent, and based on different stable target aliases.
- Persisted history contains categorical outcomes, bounded timestamps, hashed
  scope, and stable aliases only; raw hosts, addresses, SNI, and network facts are absent.
- One clean or ineligible target remains non-actionable, and stale, partial,
  cancelled, or reset evidence cannot confirm the conjunction.
- Retention, network-scope changes, backup/archive projection, and full reset
  remove or invalidate history deterministically.
- Existing single-scan replicated confirmation remains unchanged.

## Verification

- Focused Rust/Kotlin classification, persistence, archive, retention, and reset tests
- Database migration, diagnostics wire/schema, privacy, and task-contract gates
