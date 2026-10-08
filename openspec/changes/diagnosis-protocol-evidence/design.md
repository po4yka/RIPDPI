## Context

Existing observations include designated TLS/HTTP domain and throughput controls. Other families have no equivalent control designation. The general Kotlin and native classifiers are currently helper APIs without production callers. Active reports add a confirm-good diagnosis in native build_report; Kotlin StrategyRecommendationEngine projects its recommendation. This change does not activate the general classifiers.

## Goals / Non-Goals

- Goal: bounded controls and factual summaries.
- Non-goal: add new probes, infer provider intent or change wire enums.

## Decisions

Use only executed matching controls. Missing controls remain null; failed matching controls are false. Keep existing persisted codes. Require measured positive throughput control values. Remove the generic SNI correlation heuristic. In the active confirm-good report, preserve QUIC Initial evidence but leave matched-control validation unknown. Keep the recommended transport family and reduce recommendation confidence to MEDIUM with an unknown cause. DNS timing and answer differences describe observations without attributing injection, record deletion or provider delays.

## Contracts and ownership

The controls worker owns DiagnosticsFindingProjector.kt, its tests, native diagnosis classification modules/tests, monitor-engine report/summary.rs and engine report tests, the native strategy recommendation rationale, and Kotlin StrategyRecommendationEngine.kt/tests. The root owns UI/export and locale resources. No wire schema, Cargo.lock, golden or locale changes in this lane.

## Risks / Trade-offs

Older code names can imply stronger claims; current summaries expose measured facts. Root owns compatible UI labels.

## Migration Plan

No storage migration. Revert this isolated change to roll back. Validate core diagnostics unit tests, native classification tests, staticAnalysis and locked Cargo checks in the integration worktree.
