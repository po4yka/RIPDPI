---
title: Split mode editor composable below baseline
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

- [x] #task Split mode editor composable below baseline #repo/RIPDPI #area/ui #status/done 🔼

## Summary

The architecture gate reports `ModeEditorScreen` as a worsened long-composable
baseline: 684 lines versus the 635-line baseline. Split the main composable into
route shell, section renderers, field editors, validation/errors, and action
rows before more config UI work lands there.

## Audit citation

- `app/src/main/kotlin/com/poyka/ripdpi/ui/screens/config/ModeEditorScreen.kt` lines 109-110.

## Scope

- In scope: composable extraction, stable section APIs, validation/error
  rendering, action row ownership, and baseline recovery.
- Out of scope: changing config editor behavior or relay field semantics.

## Acceptance criteria

- [x] `ModeEditorScreen` no longer exceeds its long-composable baseline.
- [x] Route shell, section renderers, field editors, validation/error display,
    and action rows are independently reviewable.
- [x] Existing config UI tests and Roborazzi coverage stay green.
- [x] Architecture baseline is not increased unless explicitly accepted as debt.

## Links

- [[Epic - Finish SRP residual architecture debt]]
