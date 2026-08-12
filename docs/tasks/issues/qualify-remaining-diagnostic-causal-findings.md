---
id: DGN-1786557289726270
title: Qualify remaining diagnostic causal findings
kind: bug
status: review
area: diagnostics
priority: high
risk: standard
owner: codex
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-08-12
updated: 2026-08-12
spec_reason: regression-tested-single-module
related_tasks: []
status_detail: Qualified exported finding summaries, recommendations, direct-mode prose, and strategy evidence without changing codes or evidence; RED/GREEN regressions pass.
---

## Goal

Ensure exported diagnostic findings describe measured observations and
candidate explanations without claiming an unverified network mechanism,
interceptor, or policy as the established cause.

## Acceptance criteria

- Finding summaries and recommendations preserve their machine-readable codes,
  targets, severity, and evidence while qualifying causal interpretations.
- DNS, TLS, transport, throughput, and strategy-failure projections do not
  present injection, interception, throttling, or network-wide blocking as
  established from a single diagnostic run.
- Direct-mode and home-analysis prose reports observed failures or detection
  signals without converting them into an unsupported cause or certainty.
- Focused RED/GREEN regressions, the diagnostics module suite, static analysis,
  architecture health, and task validation pass.

## Ownership

- `core/diagnostics/src/main/kotlin/com/poyka/ripdpi/diagnostics/`
- `core/diagnostics/src/test/kotlin/com/poyka/ripdpi/diagnostics/`
- This issue, its simple-work execution record, and the generated task board
- No wire/schema fields, outcome codes, classifier versions, or golden contract
  fixtures
