---
title: Split settings JSON snapshot mappers by feature
type: task
status: backlog
area: data
priority: high
owner: unassigned
parent: epic-post-refactor-architecture-cleanup
blocks: []
blocked_by: []
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Split settings JSON snapshot mappers by feature #repo/RIPDPI #area/data #status/backlog ⏫

## Summary

Split `core/data/settings/src/main/kotlin/com/poyka/ripdpi/data/AppSettingsJson.kt` so settings import/export no longer maps every feature family in one file and two long conversion methods.

## Context

`AppSettingsJson.kt` currently owns JSON snapshot DTO conversion for DNS, proxy, desync, QUIC, adaptive fallback, WARP, relay, routing, DHT, group activation, and UI-related settings. Schema changes for unrelated features still converge in one mapper.

## Acceptance criteria

- [ ] Extract feature-owned snapshot DTOs or mapper modules for DNS, proxy/desync, adaptive, WARP, relay, routing, and UI/runtime settings.
- [ ] Keep the public JSON encode/decode entrypoints stable.
- [ ] Keep serialization names and defaults compatible with existing exported settings.
- [ ] Add or update round-trip tests covering at least two feature slices and the root aggregate.
- [ ] Reduce the architecture-health feature-spread signal for the settings JSON mapper.

## Completion outcome

Closing this task means settings backup/restore has feature-owned snapshot mappers and a small root composer. A DNS, relay, WARP, desync, adaptive, routing, or UI schema change should land in its own mapper and only touch the aggregate root when the top-level contract changes.

## Regression guardrails

- Do not keep one long `toSnapshot` or `toAppSettings` method that maps every feature.
- Do not move all feature DTOs into one new `SettingsSnapshotModels` field bag.
- Do not rename serialized fields or remove defaults as part of the split.
- Do not close the task without round-trip coverage proving compatibility for existing exports.
- Do not close the task without focused unit tests for each extracted settings snapshot mapper.

## Links

- [[Epic - Post-refactor architecture cleanup]]
