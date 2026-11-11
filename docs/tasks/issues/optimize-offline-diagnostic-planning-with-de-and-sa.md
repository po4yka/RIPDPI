---
id: DGN-1794388936359293
title: Tune DNS scoring and compact repeat diagnostics with DE and SA
kind: feature
status: review
area: diagnostics
priority: high
owner: Diagnostics
parent: null
blocked_by: []
spec_mode: required
openspec_change: offline-diagnostics-optimization
created: 2026-11-11
updated: 2026-11-11
status_detail: Implementation, local gates, independent review, and pull request description are ready.
---

## Goal

Use local observations to tune DNS scoring with Differential Evolution and to
select automatic diagnostic targets with Simulated Annealing.

## Acceptance criteria

- Deterministic bounded searches reach the existing DNS ranking and diagnostic request paths.
- Sparse or stale data keeps defaults; quarantine and full manual coverage stay intact.
- DNS tests, diagnostics tests, staticAnalysis, ci-gates, task validation, and review pass.
- A pull request contains the implementation and observed validation evidence.

## Ownership

One writer owns DNS health, core diagnostics, and this change's planning files.
No serialized shared-file lane is changed. Review agents are read-only.
