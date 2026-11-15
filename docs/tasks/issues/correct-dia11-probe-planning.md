---
id: DGN-1794743403723317
title: Correct diagnostic probe budgets and strategy evidence
kind: bug
status: review
area: diagnostics
priority: high
owner: Diagnostics maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: correct-dia11-probe-planning
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Complete diagnostic probes within their plan budgets and report only evidence that each path measured.

## Acceptance criteria

The ten DIA-11 regressions are covered by the local cases mapped in the change verification. The build and offline source gates pass on the combined tree.

## Ownership

- QUIC owner: strategy QUIC transport and runner, TCP pivot, native pivot projection, and their regression tests (F1, F2, F4).
- DNS and checkpoint owner: strategy DNS baseline and its owning resolver calls, checkpoint publication and candidate snapshot tests (F3, F5).
- Concurrency owner: connection concurrency runner and classifier, native in-path scheduling restriction and their tests (F8, F9).
- Integration owner: audit confidence, Kotlin background persistence, candidate and stage accounting, admission validation, schemas and fixtures if required, and combined validation (F6, F7, F10).
- No changes are planned for dependencies, lockfiles, protobuf, or approved baselines.
