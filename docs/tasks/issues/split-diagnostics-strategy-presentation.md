---
title: Split DiagnosticsUiStrategySupport into focused presentation mappers
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-05
updated: 2026-05-05
---

- [ ] #task Split DiagnosticsUiStrategySupport into focused presentation mappers #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Objective

Extract strategy report summary, candidate detail mapping, audit assessment presentation, resolver recommendation presentation, and signature fields out of `DiagnosticsUiStrategySupport.kt` into separate mappers.

## Context

`DiagnosticsUiStrategySupport.kt` is a large presentation mapper for approach details, strategy reports, candidate families, audit assessment, resolver recommendations, scope labels, and strategy signatures. The diagnostics sections were split, but strategy report shaping is still one change point.

Source: `app/src/main/kotlin/com/poyka/ripdpi/activities/DiagnosticsUiStrategySupport.kt:151-260`

## Acceptance criteria

- [ ] `StrategyReportSummaryMapper` handles high-level strategy report UI model.
- [ ] `CandidateDetailMapper` owns candidate family and approach detail mapping.
- [ ] `AuditAssessmentPresenter` owns audit assessment UI shaping.
- [ ] `ResolverRecommendationPresenter` owns resolver recommendation presentation.
- [ ] `StrategySignaturePresenter` owns signature field presentation.
- [ ] `DiagnosticsUiStrategySupport` delegates to the above; line count at 151-260 drops substantially.
- [ ] No visual change to diagnostics screens.

## Definition of done

Each mapper has a unit test; existing diagnostics UI tests pass unchanged.
