---
title: Decompose detection check screen responsibilities
type: task
status: done
area: ui
priority: medium
owner: unassigned
parent: epic-finish-srp-residual-architecture-debt
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [x] #task Decompose detection check screen responsibilities #repo/RIPDPI #area/ui #status/done 🔼

## Summary

`DetectionCheckScreen.kt` still contains route wiring, permission handling,
dialog host, controls, result summary, recommendations, category cards,
history/community sections, charts, and sharing behavior. Split the screen so
detection UI changes do not require wide Compose and recomposition review.

## Audit citation

- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/detection/DetectionCheckScreen.kt` lines 157-219.

## Scope

- In scope: route shell, permission host, dialogs, controls, summaries,
  recommendations, cards, history/community panels, charts, and share action
  extraction.
- Out of scope: changing detection check semantics or community comparison
  contracts.

## Acceptance criteria

- [x] Route shell delegates feature sections to focused composables.
- [x] Permission and dialog behavior is isolated from result rendering.
- [x] Architecture gate no longer reports the detection screen as an oversized
    hotspot.
- [x] Compose screenshot and unit coverage stays green.

## Links

- [[Epic - Finish SRP residual architecture debt]]
