---
title: Split settings screen state by feature
type: task
status: backlog
area: ui
priority: medium
owner: unassigned
parent: epic-finish-srp-residual-architecture-debt
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [ ] #task Split settings screen state by feature #repo/RIPDPI #area/ui #status/backlog 🔼

## Summary

`SettingsUiState` now carries the broad contract that `SettingsUiModels` used
to hide: DNS, proxy, desync, fake transport, TLS prelude, QUIC, detection
resistance, WARP, routing protection, autolearn, adaptive fallback, HTTP parser
diagnostics, strategy packs, service status, and reset flags.

## Audit citation

- `app/src/main/kotlin/com/poyka/ripdpi/activities/SettingsUiState.kt` lines 11-68.

## Scope

- In scope: feature-owned settings state modules, reset flag ownership, and
  ViewModel aggregation boundaries.
- Out of scope: changing settings persistence or default values.

## Acceptance criteria

- [ ] Feature state is grouped by owner and exposed through narrow section
    models.
- [ ] Unrelated settings do not converge in one mutable aggregate contract.
- [ ] Reset flags live with the feature that owns the reset behavior.
- [ ] Settings ViewModel and UI tests cover the new composition.

## Links

- [[Epic - Finish SRP residual architecture debt]]
