---
id: DGN-1791482054896448
title: Report exhausted UDP DNS timeouts without recovery claims
kind: bug
status: review
area: diagnostics
priority: medium
owner: DNS classifier agent
parent: null
blocked_by: []
spec_mode: required
openspec_change: report-exhausted-udp-dns-timeouts
created: 2026-10-08
updated: 2026-10-08
---

## Goal

Distinguish exhausted UDP DNS timeouts from observed recovery.

## Acceptance criteria

- Exhausted timeout reports unavailability without a blocking or recovery claim.
- Successful retry remains unstable UDP DNS.
- Focused tests and independent review pass.

## Ownership

Classifier agent owns the DNS outcome and tests in its isolated worktree. Root owns combined board generation and integration. No shared schema, lockfile, golden, or locale edits.
