---
id: DGN-1790433260349809
title: Correct diagnostics recommendation and regression selection
kind: bug
status: doing
area: diagnostics
priority: high
owner: Diagnostics recommendation writer
parent: null
blocked_by: []
spec_mode: required
openspec_change: correct-diagnostics-recommendation-regression-selection
created: 2026-09-26
updated: 2026-09-26
---

## Goal

Use all relevant diagnostic evidence when recommending a strategy or resolver, and compare a home analysis run with the last completed run.

## Acceptance criteria

- A TCP freeze after the transfer threshold contributes the same threshold-block signal as a TCP cutoff when real reachability failure makes a raw-path recommendation actionable.
- A home regression delta uses the last completed run, even when an older run has more sessions or the same session count. The current run is never its own predecessor.
- A suppressed secondary DNS trigger does not hide a later independent primary DNS trigger.
- Each defect has a failing regression test before its fix; focused and module unit gates pass.

## Ownership

- This writer owns `StrategyRecommendationEngine.kt`, `ResolverRecommendationEngine.kt`, `HomeCompositeRunJobs.kt`, `HomeCompositeOutcomeFinalizer.kt`, `DefaultDiagnosticsHomeCompositeRunService.kt`, and focused tests in `:core:diagnostics`.
- Other diagnostics writers own export, data stores, DPI probes, and RKN. This task does not edit their files.
- This writer owns this portfolio task, its OpenSpec change, and generated board updates until handoff.
