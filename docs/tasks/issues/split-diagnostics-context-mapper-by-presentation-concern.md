---
title: Split diagnostics context mapper by presentation concern
type: task
status: done
area: diagnostics
priority: medium
owner: unassigned
parent: epic-finish-srp-residual-architecture-debt
blocks: []
blocked_by: []
created: 2026-05-06
updated: 2026-05-06
---

- [x] #task Split diagnostics context mapper by presentation concern #repo/RIPDPI #area/diagnostics #status/done 🔼

## Summary

`DiagnosticsUiContextSupport.kt` remains oversized after section-builder
splits. Split diagnostics context presentation by network snapshot, overview
context, transport fields, redaction, and timestamp/label helpers.

## Audit citation

- `app/src/main/kotlin/com/poyka/ripdpi/activities/DiagnosticsUiContextSupport.kt` lines 9-101.

## Scope

- In scope: diagnostics UI context mappers, redaction helpers, timestamp/label
  formatting, network snapshot projection, overview context, and transport
  fields.
- Out of scope: changing exported diagnostics data or redaction policy without
  explicit review.

## Acceptance criteria

- [x] Context mapping is split by presentation concern.
- [x] LongMethod and complexity suppressions are removed or materially reduced.
- [x] Redaction helpers stay centralized enough to avoid privacy regressions.
- [x] Diagnostics UI mapper tests cover the extracted pieces.

## Links

- [[Epic - Finish SRP residual architecture debt]]
