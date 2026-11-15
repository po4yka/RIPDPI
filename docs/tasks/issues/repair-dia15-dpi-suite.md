---
id: DGN-1794745065716076
title: Repair DPI suite probe evidence and execution
kind: bug
status: review
area: diagnostics
priority: high
owner: DIA-15 repair
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-dia15-dpi-suite
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Repair TCP16, SNI, Telegram, and ECH diagnostics so the suite reports measured outcomes.

## Acceptance criteria

- Address the 14 DIA-15 findings and cover the corrected behavior with regression checks.
- Keep one implementation commit per finding where it can stand on its own.
- Run combined source gates before integration. Record device and external evidence separately.

## Ownership and serialized lanes

- TCP16 and SNI, branch fix/dia15-tcp-sni, owns the TCP16 and SNI runners, models, target parsing, and owning tests.
- Telegram, branch fix/dia15-telegram, owns the transfer and DC runners, models, and owning tests.
- ECH, branch fix/dia15-ech, owns the DNS and ECH probes, Kotlin to Rust contract, and owning tests.
- Coordinator, branch fix/dia15-diagnostics, owns OpenSpec, suite scheduling and aggregation, UI consumers, locales, generated exports, and integration.
- Reviewers work read-only. Each writer uses a dedicated worktree.
