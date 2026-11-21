---
id: DGN-1795238144093160
title: Scope diagnostics and policy updates to measured evidence
kind: bug
status: review
area: diagnostics
priority: high
owner: Diagnostics engineering
parent: null
blocked_by: []
spec_mode: required
openspec_change: verified-diagnostic-evidence
created: 2026-11-21
updated: 2026-11-21
---

## Goal

Keep diagnostic conclusions and automatic policy updates within the path that produced the evidence.

## Acceptance criteria

- A stale proxy runtime or physical network cannot update learned direct-path policy.
- A revoked VPN route leaves measurements available but cannot support a current path verdict.
- Unrelated SNI results cannot establish a shared cause. DNS changes require verified recovery on the affected query.
- Targeted Rust and Kotlin regression tests pass.

## Ownership

- Integration owns runtime evidence, route finalization, task metadata, and validation.
- SNI worker owns the native classification crate.
- DNS worker owns resolver recommendation, DNS workflow, DNS finalization, and their tests. Integration owns the route finalization function and integrates the shared file serially.
