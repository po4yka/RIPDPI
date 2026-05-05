---
title: Split RipDpiState theme tokens by component family
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

- [ ] #task Split RipDpiState theme tokens by component family #repo/RIPDPI #area/ui #status/backlog 🔼

## Objective

Split `RipDpiState.kt` state roles and resolvers by component family while keeping `RipDpiThemeTokens` as the single public facade.

## Context

`RipDpiState.kt` defines roles and resolver state for buttons, icon buttons, rows, banners, actuators, route availability, text fields, chips, switches, and more in one nearly 1k-line token module. Token changes for unrelated components still share the same file and review surface.

Source: `app/src/main/kotlin/com/poyka/ripdpi/ui/theme/RipDpiState.kt:12-75`

## Acceptance criteria

- [ ] Each component family (buttons, rows, chips, banners, text fields, switches, actuators) has its own state/resolver file in the theme package.
- [ ] `RipDpiThemeTokens` (or equivalent public object) re-exports all families as before — no call-site changes required.
- [ ] `RipDpiState.kt` no longer exceeds ~100 lines.
- [ ] No Compose recomposition regression; Roborazzi theme golden passes.

## Definition of done

`RipDpiState.kt` is reduced to a facade or removed; all component families compile; goldens pass.
