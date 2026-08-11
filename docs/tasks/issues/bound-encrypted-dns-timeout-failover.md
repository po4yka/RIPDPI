---
id: SVC-1786488973639528
title: Bound encrypted DNS timeout failover
kind: bug
status: review
area: service
priority: high
risk: standard
owner: codex
parent: null
blocked_by: []
spec_mode: required
openspec_change: improve-dns-timeout-failover
created: 2026-08-12
updated: 2026-08-12
related_tasks: []
status_detail: Implementation and local verification complete; awaiting exact-SHA hosted CI.
---

## Goal

Bound VPN startup delay when encrypted DNS paths time out, without permanently
excluding a resolver that experienced a transient timeout.

## Acceptance criteria

- The first timeout within an encrypted resolver path's first three queries
  activates the next unattempted encrypted candidate.
- A timeout after the bootstrap window still requires two consecutive failure
  events before failover.
- Timeout-only failures are not persisted as network-blocked paths; SNI, TLS,
  and certificate block evidence keeps its existing persistence behavior.
- Exhaustion remains fail-closed with no plaintext DNS override.
- Focused RED/GREEN regression, full service tests, `staticAnalysis`, and strict
  OpenSpec/task validation pass.
