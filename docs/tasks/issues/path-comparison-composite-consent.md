---
id: DGN-1786562358587116
title: Honor composite consent for path comparison
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
status_detail: Composite path-comparison consent is explicit and selective; focused and full diagnostics tests plus static analysis pass.
---

## Goal

Run the targeted in-path comparison selected by a user-started Full Analysis
without rejecting its sensitive profile for missing internal consent.

## Acceptance criteria

- The Full Analysis path-comparison stage explicitly carries the user's
  composite-run consent into scan admission.
- A standalone sensitive-profile start remains fail-closed unless its caller
  explicitly supplies consent.
- The regression test, diagnostics unit tests, static analysis, task
  validation, and architecture-health checks pass.

## Evidence

The fresh archive under `/private/tmp/ripdpi-fresh-analysis-20260812` records
`path_comparison` as failed without a session because scan admission returned
`Explicit consent is required before running this diagnostics profile`.
