---
id: DGN-1793896868711593
title: Preserve diagnostics evidence and validated recommendations
kind: bug
status: doing
area: diagnostics
priority: high
owner: Diagnostics evidence repair
parent: null
blocked_by: []
spec_mode: required
openspec_change: diagnostics-evidence-repair
created: 2026-11-05
updated: 2026-11-05
---

## Goal

Keep measured evidence, strategy configuration, and user-facing conclusions consistent across diagnostics.

## Acceptance criteria

- Preserve the measured TCP strategy when composing a recommendation and reject an inconsistent configuration before application.
- Confirm VPN access only from a complete, current, successful verification report.
- Keep unavailable and inconclusive DPI probes distinct from clean results and confirmed interference.
- Preserve useful recommendations when only one transport lane has a verified winner.
- Compare the same domain protocols and service components without hiding failures or rejecting supported TLS versions.
- Require completed transfer evidence for an alternative-SNI success.
- Use suitable throughput targets and keep short responses and unrelated failures distinct from a failed throughput measurement.
- Keep TCP reset observations distinct from unverified injection, origin, and receive-window claims.
- Observe regression failures before each fix, then pass affected tests, static checks, contract gates, and independent review.

## Ownership

- The primary writer owns this task, OpenSpec artifacts, catalog sources and generated catalog, locale files, and Home verification/application code.
- The strategy writer owns native strategy configuration and recommendation code, the serialized recommendation/schema contracts and their consumers and tests, except the Home and comparison files owned by other writers, in a separate worktree. This writer owns diagnostics field manifests. After source review, the golden writer owns the ten approved diagnostics fixture paths in a separate worktree.
- The Kotlin writer owns `ComparisonScanCoordinator.kt`, `dpi/DpiSuiteVerdictAggregator.kt`, and their tests, in a separate worktree.
- The native probe writer owns diagnostics runner TCP/throughput probes, fat-header classification, diagnosis throughput gating, and their tests, in a separate worktree.
- No writer changes another lane. The primary writer integrates local commits and runs combined-tree gates. Golden fixture changes require scoped user authorization.
