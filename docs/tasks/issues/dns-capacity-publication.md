---
id: DNS-1788602983485108
title: Release DNS lookup capacity before publishing results
kind: bug
status: doing
area: dns
priority: high
owner: DNS executor audit
parent: null
blocked_by: []
spec_mode: required
openspec_change: dns-capacity-publication
created: 2026-09-05
updated: 2026-09-05
---

## Goal

Prevent a completed DNS lookup from retaining capacity after its result reaches the caller. Full CI 33958739073 reproduced a false Busy in the existing panic recovery test.

## Acceptance criteria

- Release lookup capacity before result publication for successful and caught-panic lookups.
- Preserve bounded concurrency, timeout handling and shutdown.
- Pass the complete transport crate, strict Clippy and required hosted CI.

## Ownership

The isolated DNS writer owns only diagnostics-transport/src/transport/address.rs. Audit integration owns this task, specification, audit report and integration in the main-candidate worktree. Review agents are read-only. No dependency, schema, locale or baseline changes.
