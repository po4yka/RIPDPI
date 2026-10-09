---
id: DGN-1791519134625558
title: Add a selective availability diagnostic matrix
kind: feature
status: review
area: diagnostics
priority: high
owner: matrix-integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: selective-availability-matrix
created: 2026-10-09
updated: 2026-10-09
status_detail: Implementation and local gates passed; approved shared fixtures now pass Kotlin and Rust contract checks. Remote CI and device evidence remain pending.
---

## Goal

Provide a manual diagnostic matrix that compares bounded HTTPS availability across declared-available, domestic, global, and optional user targets. Preserve observations separately from provider-cause hypotheses.

## Acceptance criteria

- A visible manual profile runs repeated DNS, TCP, verified TLS, HTTP and bounded body measurements.
- Results distinguish complete response, partial response, server rejection, transport failure, and unmeasured stages.
- Independent cohort controls, partial runs, invalid controls and network-scope changes cannot produce an unsupported allowlist verdict.
- The app displays a readable matrix and supports bounded user targets; history and redacted exports preserve the evidence.
- Local tests, contract checks, static analysis and locale lint pass; remote CI and device evidence are reported separately.

## Ownership

- Native writer owns native/rust source and tests, including native wire types and registries. No lockfile or schema-version changes without integration agreement.
- UI writer owns app source, app tests, and all locale resource sets.
- Catalog writer owns build-logic/convention diagnostics catalog source and tests in the dedicated matrix catalog worktree.
- Integration writer owns core/diagnostics Kotlin contracts, planning, request plumbing, summary/export integration, task/OpenSpec files and generated board.
- Golden fixtures are serialized through the golden-blesser after explicit fixture-family authorization. Baselines and dependency versions are not changed.
