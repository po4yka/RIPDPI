---
title: Split DiagnosticsUiModels.kt — extract business derivation into mappers
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

- [ ] #task Split DiagnosticsUiModels.kt — extract business derivation into mappers #repo/RIPDPI #area/ui #status/backlog ⏫

## Objective

Remove business-derivation extension properties and domain-level string formatting from `DiagnosticsUiModels.kt`, moving them into a dedicated `DiagnosticsUiModelMappers.kt` file.

## Context

`DiagnosticsUiModels.kt` (860 LOC) defines 30+ UI model data classes but also contains extension properties encoding business logic (e.g., `isStrategyProbe`, `isFullAudit` at lines 34–38 that determine scan execution behavior, not display formatting). `DpiFailureClass` enum has a `label: String` field used as a UI label — string formatting in a domain enum violates the layer boundary. Business derivation in UI model files makes the models untestable as pure data objects and creates implicit coupling between display and execution logic.

Source: `app/src/main/kotlin/com/poyka/ripdpi/activities/DiagnosticsUiModels.kt:1-860`

## Acceptance criteria

- [ ] `DiagnosticsUiModels.kt` contains only `@Immutable`/`@Stable` data class and enum declarations — no extension properties with behavior.
- [ ] `DiagnosticsUiModelMappers.kt` contains all `toUiModel()` conversions and derived properties (`isStrategyProbe`, `isFullAudit`, etc.).
- [ ] `DpiFailureClass.label` moved to a string resource resolver or display mapper; the enum itself has no string fields.
- [ ] All existing callers of the moved properties compile without change (same function name, different file).
- [ ] No behavioral change; existing diagnostics tests pass.

## Definition of done

`DiagnosticsUiModels.kt` contains zero extension properties; all derived properties compile from `DiagnosticsUiModelMappers.kt`.
