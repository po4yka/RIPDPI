---
title: Move AdvancedSettingsScreen taxonomy into feature-specific settings modules
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

- [ ] #task Move AdvancedSettingsScreen taxonomy into feature-specific settings modules #repo/RIPDPI #area/ui #status/backlog 🔼

## Objective

Move setting identifiers and binders out of `AdvancedSettingsScreen.kt` into feature-specific settings modules, exposing only a registry/list to the screen shell.

## Context

`AdvancedSettingsScreen.kt` centralizes toggle/text/option setting enums for diagnostics, command-line args, desync, QUIC, WARP, host autolearn, adaptive fallback, entropy, routing protection, and more. Adding any feature setting modifies the same taxonomy and binder surface.

Source: `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/settings/AdvancedSettingsScreen.kt:52-158`

## Acceptance criteria

- [ ] Each feature (desync, QUIC, WARP, diagnostics, routing-protection, etc.) owns its setting identifiers and binder in its own module/file.
- [ ] `AdvancedSettingsScreen` consumes a `List<SettingsSection>` registry and renders it; it contains no feature-specific logic.
- [ ] Adding a new feature setting requires touching only its own module, not `AdvancedSettingsScreen`.
- [ ] No visual regression verified via Roborazzi or manual review.

## Definition of done

`AdvancedSettingsScreen.kt:52-158` is replaced by a registry consumer; screen compiles and renders correctly.
