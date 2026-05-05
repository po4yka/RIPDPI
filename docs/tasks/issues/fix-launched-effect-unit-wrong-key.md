---
title: Fix LaunchedEffect(Unit) wrong key in 3 composable routes
type: task
status: backlog
area: ui
priority: medium
owner: unassigned
parent: epic-srp-and-architecture-refactoring
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Fix LaunchedEffect(Unit) wrong key in 3 composable routes #repo/RIPDPI #area/ui #status/backlog 🔼

## Objective

Change `LaunchedEffect(Unit)` to `LaunchedEffect(viewModel)` in all three affected routes so SharedFlow effects collection restarts if the ViewModel instance changes.

## Context

Three composables use `LaunchedEffect(Unit)` to collect `viewModel.effects` (a SharedFlow). `Unit` as the key means the coroutine is launched once and never restarted — if the composable is recomposed with a different ViewModel instance (process recreation, back-stack manipulation in tests), the effect continues collecting from the stale instance. The correct pattern is already used in `DiagnosticsRoute.kt:43` (`LaunchedEffect(viewModel)`). `ModeEditorRoute.kt` additionally launches two separate `LaunchedEffect(Unit)` blocks — one for initialization reading `uiState.editingPreset` at composition time (a race with initial state) and one for effects collection.

Affected files:
- `AdvancedSettingsRoute.kt:58`
- `ModeEditorRoute.kt:135`
- `ModeEditorRoute.kt:161`

## Acceptance criteria

- [ ] All three `LaunchedEffect(Unit)` replaced with `LaunchedEffect(viewModel)`.
- [ ] The two `LaunchedEffect(Unit)` blocks in `ModeEditorRoute.kt` merged into one `LaunchedEffect(viewModel)` block with the initialization logic moved inside.
- [ ] No behavioral change in normal navigation; verified by UI tests.

## Definition of done

Zero `LaunchedEffect(Unit)` calls remain in route-level composables that collect ViewModel SharedFlows; CI green.
