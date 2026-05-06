---
title: Split advanced settings binder by feature
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

- [x] #task Split advanced settings binder by feature #repo/RIPDPI #area/ui #status/done 🔼

## Summary

`AdvancedSettingsBinder` is the mutation counterpart to the centralized
taxonomy. It imports and mutates adaptive, desync, DNS, routing, tunnel, and
WARP settings from one 711-line file. Split write paths into feature-owned
binders registered with the settings shell.

## Audit citation

- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/settings/AdvancedSettingsBinder.kt` lines 80-171.

## Scope

- In scope: advanced settings mutation handlers, feature-owned binders,
  settings shell registration, and mutation tests.
- Out of scope: changing setting defaults or persistence wire contracts.

## Acceptance criteria

- [x] Adaptive, desync, DNS, routing, tunnel, and WARP mutations live in
    feature-owned binders.
- [x] Settings shell registers binders without depending on each feature's
    mutation internals.
- [x] Adding a new feature setting does not require editing a large shared
    binder.
- [x] Existing settings mutation tests stay green.

## Links

- [[Epic - Finish SRP residual architecture debt]]
