---
title: Split DetectionCheckScreen into focused composable components
type: task
status: backlog
area: ui
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Split DetectionCheckScreen into focused composable components #repo/RIPDPI #area/ui #status/backlog 🔼

## Objective

Extract route/permission handling, result summary, category cards, recommendations, history/community sections, and dialog hosts from `DetectionCheckScreen` so detection UI changes are localized.

## Context

`DetectionCheckScreen` owns route permission actions, methodology dialog state, pull-to-refresh, score/verdict rendering, auto-tune, recommendations, history, community stats, and collapsible category cards in one screen file.

Source: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/detection/DetectionCheckScreen.kt:131-150`

## Acceptance criteria

- [ ] `DetectionPermissionHandler` composable owns route/permission action delegation.
- [ ] `DetectionResultSummary` composable owns score/verdict and auto-tune rendering.
- [ ] `DetectionCategoryCards` composable owns collapsible category card list.
- [ ] `DetectionRecommendations` composable owns recommendation list rendering.
- [ ] `DetectionHistoryCommunitySection` composable owns history and community stats.
- [ ] `DetectionDialogHost` composable owns methodology dialog state.
- [ ] `DetectionCheckScreen` becomes a thin coordinator composing the above.
- [ ] Roborazzi detection screen golden passes.

## Definition of done

`DetectionCheckScreen.kt` body is substantially reduced; each extracted composable has a preview test; no visual regression.
