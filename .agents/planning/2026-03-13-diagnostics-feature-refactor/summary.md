# Diagnostics Feature Refactor Summary

## Artifacts Created

- `rough-idea.md`
- `idea-honing.md`
- `research/current-feature-analysis.md`
- `research/testing-baseline.md`
- `requirements.md`
- `design.md`
- `implementation-plan.md`

## Design Overview

The plan treats `DiagnosticsManager.kt` and `DiagnosticsScreen.kt` as one diagnostics feature boundary, while accounting for the actual runtime adapter layer in `DiagnosticsViewModel`. The safe path is to preserve the current manager API and `DiagnosticsUiState` contract first, then decompose each oversized file behind those stable feature boundaries.

The manager refactor is staged around cohesive collaborators for scan workflow, automatic probing, recommendation logic, query/persistence behavior, and share/archive building. The screen refactor is staged around route/container logic, sections, bottom sheets, and pure UI helpers, with local UI state left at the lowest appropriate owner.

## Implementation Plan Overview

The implementation plan is explicitly test-first:

1. strengthen characterization coverage
2. extract pure screen helpers
3. extract screen sections and sheets
4. extract manager recommendation/planning logic
5. extract manager share/archive builders
6. extract manager query/persistence services
7. extract manager async workflow
8. run final validation and handoff

Each step has a validation gate and a suggested commit boundary so the refactor can be rolled back slice by slice.

## Suggested Next Steps

- add the planning files to context:
  - `/context add .agents/planning/2026-03-13-diagnostics-feature-refactor/**/*.md`
- review `requirements.md`, `design.md`, and `implementation-plan.md`
- when ready to execute the refactor, start a Ralph run against the implementation plan rather than doing a single manual rewrite

## Ralph Handoff

When you want to start implementation, use one of these commands yourself:

- `ralph run --config presets/pdd-to-code-assist.yml --prompt "Implement the diagnostics feature refactor from .agents/planning/2026-03-13-diagnostics-feature-refactor/implementation-plan.md, preserving behavior and following the test-first milestones in the plan."`
- `ralph run -c ralph.yml -H builtin:pdd-to-code-assist -p "Implement the diagnostics feature refactor from .agents/planning/2026-03-13-diagnostics-feature-refactor/implementation-plan.md, preserving behavior and following the test-first milestones in the plan."`
