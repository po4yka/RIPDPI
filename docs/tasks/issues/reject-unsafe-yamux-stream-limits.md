---
id: TRN-1794377577809835
title: Reject unsafe yamux stream limits
kind: bug
status: review
area: transport
priority: high
owner: Transport maintainers
parent: null
blocked_by: []
spec_mode: required
openspec_change: yamux-stream-limits
created: 2026-11-11
updated: 2026-11-11
---

## Goal

Reject oversized yamux stream limits before carrier I/O without changing the receive budget.

## Acceptance criteria

Profile and direct-constructor boundary tests pass without panic. Affected crate tests, Clippy, rustfmt, locked metadata and ci-gates pass.
