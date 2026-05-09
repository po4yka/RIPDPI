---
title: Split diagnostics database DAOs by domain
type: task
status: backlog
area: data
priority: medium
owner: unassigned
parent: epic-post-refactor-architecture-cleanup
blocks: []
blocked_by: []
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Split diagnostics database DAOs by domain #repo/RIPDPI #area/data #status/backlog 🔼

## Summary

Split `core/diagnostics-data/src/main/kotlin/com/poyka/ripdpi/data/diagnostics/DiagnosticsDatabase.kt` so entities and DAO operations are grouped by diagnostics domain instead of one broad persistence contract.

## Context

The file defines entities and DAO methods for profiles, target packs, scan sessions, probe results, snapshots, context snapshots, telemetry samples, native events, exports, bypass usage, remembered policies, DNS path preferences, edge preferences, and retention cleanup.

## Acceptance criteria

- [ ] Group entities into focused files for catalog/profile, scan/probe, snapshot/context, telemetry/events, exports, and remembered policy/network preference data.
- [ ] Split the broad DAO into domain DAOs or clearly bounded DAO interfaces.
- [ ] Keep Room database registration and migrations compatible.
- [ ] Add or update DAO tests for at least one migrated domain and retention behavior.
- [ ] Reduce file LOC and function-count pressure in `DiagnosticsDatabase.kt`.

## Completion outcome

Closing this task means diagnostics persistence is split into domain DAOs and entity files while the Room database remains the composition point. Profile/catalog, scans/probes, snapshots/context, telemetry/events, exports, and remembered-policy data should have separate review surfaces.

## Regression guardrails

- Do not move all DAO methods into one renamed `DiagnosticsStore`.
- Do not mix retention cleanup with unrelated domain query APIs unless behind a small retention coordinator.
- Do not change table names, indices, or migrations accidentally while splitting files.
- Do not close the task without DAO tests or migration checks for the touched domains.
- Do not close the task without focused unit/instrumented persistence tests for each extracted DAO domain.

## Links

- [[Epic - Post-refactor architecture cleanup]]
