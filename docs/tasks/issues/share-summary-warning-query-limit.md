---
id: DGN-1790431550325964
title: Preserve older diagnostics warnings in share summaries
kind: bug
status: review
area: diagnostics
priority: medium
owner: Diagnostics export agent
parent: null
blocked_by: []
spec_mode: required
openspec_change: share-summary-warning-query-limit
created: 2026-09-26
updated: 2026-09-26
---

## Goal

Share summaries retain the latest 50 warning and error native events even when newer information events exist.

## Acceptance criteria

- A selected-session summary includes an older warning behind 50 newer information events from that session.
- A live summary applies the same warning filter before the result limit.
- Room queries keep session scoping and descending event order.
- Focused diagnostics and diagnostics-data tests, OpenSpec validation, and task contracts pass.

## Parallel ownership

This task owns `DiagnosticsShareSummaryBuilder.kt`, a new share-warning test, the warning-event DAO methods, artifact read/query store contracts and adapters, their fake, and relevant Room tests. Other diagnostics paths are owned by parallel agents. No schema or migration file changes.
