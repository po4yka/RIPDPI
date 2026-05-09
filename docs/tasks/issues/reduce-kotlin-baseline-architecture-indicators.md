---
title: Reduce Kotlin baseline architecture indicators
type: task
status: backlog
area: ui
priority: medium
owner: unassigned
parent: epic-post-refactor-architecture-cleanup
blocks: []
blocked_by: []
created: 2026-05-09
updated: 2026-05-09
---

- [ ] #task Reduce Kotlin baseline architecture indicators #repo/RIPDPI #area/ui #status/backlog 🔼

## Summary

Reduce the remaining Kotlin P3 architecture-health baseline indicators in diagnostics, home diagnostics, settings, and detection UI surfaces.

## Context

The current architecture-health report has 57 baseline-covered P3 indicators. The highest-signal Kotlin clusters are `DiagnosticsScanActions.kt`, `DiagnosticsUiStateAssembler.kt`, `DiagnosticsTelemetryMetrics.kt`, `MainHomeDiagnosticsActions.kt`, `MainHomeDiagnosticsUiState.kt`, `DiagnosticsStrategyProbeReport.kt`, settings sections/routes, DNS settings cards/custom resolver, and detection/home screens.

## Acceptance criteria

- [ ] Split diagnostics scan actions and diagnostics UI assembly by workflow/state slice.
- [ ] Split home diagnostics actions/state into smaller command and presentation modules.
- [ ] Split large diagnostics report composables into summary/detail/candidate sections.
- [ ] Reduce feature-family spread in settings and detection surfaces where practical.
- [ ] Architecture-health current indicator count drops without increasing baselines.
- [ ] Targeted Compose/unit tests for touched presentation mappers remain green.

## Completion outcome

Closing this task means the highest-signal Kotlin architecture indicators have moved from broad presentation/config surfaces into tab-owned, feature-owned, or workflow-owned modules, and the architecture-health report shows an actual reduction rather than a renamed baseline.

## Regression guardrails

- Do not move large composable bodies into generic `Widgets`, `Support`, or `Helpers` files that become new hotspots.
- Do not centralize feature settings, diagnostics state, or home actions in one new registry without feature-owned binders.
- Do not suppress new long-method/complexity warnings as part of the split.
- Do not close the task if architecture-health still reports the same Kotlin feature-spread shape under new filenames.
- Do not close the task without focused unit or Compose tests for each refactored presentation/state slice, or a written explanation of existing coverage.

## Links

- [[Epic - Post-refactor architecture cleanup]]
