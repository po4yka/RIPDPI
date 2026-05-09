---
title: Split native proxy UI preference mappers
type: task
status: backlog
area: android
priority: high
owner: unassigned
parent: epic-post-refactor-architecture-cleanup
blocks: []
blocked_by: []
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Split native proxy UI preference mappers #repo/RIPDPI #area/android #status/backlog ⏫

## Summary

Split `core/engine/src/main/kotlin/com/poyka/ripdpi/core/RipDpiProxyUIPreferences.kt` into feature-specific native preference mappers while keeping the aggregate native config DTO stable.

## Context

The file is still the Kotlin-to-native bridge for command-line mode, desync, fake transport, QUIC, host packs, relay, WARP, runtime context, logging, root helper, and environment settings. Native config bridge changes for one feature still require reviewing the whole conversion surface.

## Acceptance criteria

- [ ] Extract per-feature mapper modules for desync/fake transport, QUIC, relay, WARP, runtime/log context, and root-helper/environment settings.
- [ ] Keep `RipDpiProxyUIPreferences` as a small aggregate DTO or facade.
- [ ] Preserve native JSON/config output compatibility.
- [ ] Add focused mapper tests or golden assertions for representative feature slices.
- [ ] Reduce feature-family spread for the native preference bridge.

## Completion outcome

Closing this task means the native proxy UI preference bridge is an aggregate of feature mappers, not the place where every UI setting becomes native runtime config. Feature changes should be reviewable in the matching mapper with stable output at the root.

## Regression guardrails

- Do not leave all feature conversion inside `fromSettings` or one replacement builder.
- Do not couple relay, WARP, desync, root-helper, and log-context mapping through shared mutable builder state.
- Do not change native config field names or semantics unless a separate compatibility task owns it.
- Do not close the task if architecture-health still flags the bridge as a feature-spread hub.
- Do not close the task without focused unit or golden tests for each extracted native preference mapper.

## Links

- [[Epic - Post-refactor architecture cleanup]]
