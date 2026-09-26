---
id: DGN-1790434142197991
title: Avoid external public-IP lookup in automatic diagnostics
kind: bug
status: review
area: diagnostics
priority: high
owner: Diagnostics network privacy agent
parent: null
blocked_by: []
spec_mode: required
openspec_change: avoid-public-ip-lookup-in-automatic-diagnostics
created: 2026-09-26
updated: 2026-09-26
---

## Goal

Automatic background diagnostics do not contact the external public-IP resolvers for pre-scan or post-scan snapshots. User-initiated diagnostics retain their current public-IP behavior.

## Acceptance criteria

- Both automatic snapshot captures call the provider with `includePublicIp=false`.
- A DNS-corrected re-probe started by an automatic scan also disables public-IP lookup.
- User-initiated captures still request public IP.
- Recording-provider regression tests, module unit tests, detekt, OpenSpec, and task validation pass.

## Parallel ownership

This task owns diagnostics `DiagnosticsScanRequestFactory.kt`, `DiagnosticsScanFinalizationServices.kt`, the recording test provider, related tests, and this task/OpenSpec. The source-level snapshot projection and diagnostics-data migration are separate slices. The product choice for a complete public-IP opt-in remains open.
