---
title: Split SettingsUiModels into per-feature state packages
type: task
status: backlog
area: ui
priority: high
owner: unassigned
parent: epic-srp-and-architecture-refactoring
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Split SettingsUiModels into per-feature state packages #repo/RIPDPI #area/ui #status/backlog ⏫

## Objective

Break `SettingsUiModels.kt` into feature-scoped state packages so unrelated feature changes no longer share one model surface.

## Context

`SettingsUiModels.kt` imports and defines state/defaults across desync, adaptive policy, encrypted DNS, WARP, routing protection, host packs, strategy packs, and protobuf mutation effects. This is a UI-side field bag: unrelated feature changes still share one model surface.

Source: `app/src/main/kotlin/com/poyka/ripdpi/activities/SettingsUiModels.kt:4-73`

## Acceptance criteria

- [ ] Separate state classes/files for: `DesyncSettingsState`, `DnsSettingsState`, `WarpSettingsState`, `RelaySettingsState`, `RoutingProtectionSettingsState`, `DiagnosticsSettingsState`, `HostStrategyPacksState`.
- [ ] A small `SettingsScreenState` aggregates only the feature-package references.
- [ ] No feature package imports another feature package's state.
- [ ] Settings screen compiles and renders correctly with no visual regression.

## Definition of done

`SettingsUiModels.kt` is removed or reduced to the thin aggregator; Roborazzi settings screen golden passes.
