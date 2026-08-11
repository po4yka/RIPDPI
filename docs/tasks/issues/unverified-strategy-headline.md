---
id: DGN-1786463720924576
title: Correct unverified strategy headline
kind: bug
status: done
area: diagnostics
priority: high
risk: standard
owner: codex
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-08-11
updated: 2026-08-11
spec_reason: regression-tested-single-module
related_tasks: []
status_detail: DNS-only headline regression covered; diagnostics tests and staticAnalysis pass
closed_at: "2026-08-11T16:05:20Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: RED reproduced by DiagnosticsHomeAuditOutcomeBuilderTest; focused test, full core diagnostics tests, and staticAnalysis pass
---

## Goal

Report DNS-only application without implying that an unverified network-path strategy was applied.

## Acceptance criteria

- A DNS-only outcome uses a headline that explicitly says no network-path strategy was confirmed.
- A validated strategy application keeps the existing settings-applied headline.
- Diagnostics unit tests and static analysis pass.
