---
id: DGN-1794734107413188
title: Correct DIA-08 Telegram diagnostic evidence and transport
kind: bug
status: doing
area: diagnostics
priority: high
owner: Diagnostics maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: repair-telegram-diagnostic-evidence
created: 2026-11-15
updated: 2026-11-15
---

## Goal

Report Telegram HTTP, DC TCP, and WSS opening measurements accurately, with separate results for failure and interruption.

## Acceptance criteria

- Cover the audit findings P2-01 through P2-08 and P3-09 through P3-10 with owning tests.
- Validate production Rust/Kotlin wiring, source gates, and the locked workspace.
- Record which live Telegram and physical-device checks remain open.

## Ownership

- HTTP worktree: diagnostic transfer, URI validation, native request validation,
  and Android preflight. P2-02, P2-03, P3-09 and transfer control in P2-05.
- Paths worktree: Telegram DC/WSS transport, dormant TCP verdict, and bundled
  catalog source and asset. P2-04, P2-08, P3-10.
- Report worktree: aggregate, observation, report schema, app card, Share, and
  serialized locale and report API snapshot updates. P2-01, P2-06, P2-07.
- Integration worktree: in-progress checkpoint publication in P2-05 after the
  report handoff, specification, and final gates.
