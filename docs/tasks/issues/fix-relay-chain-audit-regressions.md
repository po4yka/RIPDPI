---
id: RLY-1794335337545384
title: Fix relay chain audit regressions
kind: bug
status: doing
area: relay
priority: high
owner: Relay chain maintenance
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-relay-chain-audit-regressions
created: 2026-11-10
updated: 2026-11-10
---

## Goal

Fix F1–F5 from the RLY-16 audit: preserve selected routes, reject incomplete hops, drain AnyTLS work, and report all configured roles and trust domains.

## Acceptance criteria

- Regression tests cover all five findings.
- Affected Kotlin and Rust tests, static analysis and source gates pass.
- Independent review and async cancellation review have no unresolved findings.
- Each finding has a separate fix commit; integrate into main and push after validation.

## Ownership

- profiles: app profile mapper, draft validation, service chain admission; separate new test files.
- lifecycle: AnyTLS session ownership and adapter; chain.rs factory shutdown only.
- telemetry_trust: telemetry.rs, builder, chain.rs attempt recording only; UI trust helper and existing UI state tests.
- coordinator: task/spec records, combined generated API snapshots and integration.
- Separate agent worktrees. No shared lockfile, proto, locale, or golden writes. See design.md for exact paths.
