---
id: DGN-1795361889557414
title: Diagnose HTTPS downloads that stall with larger bodies
kind: feature
status: review
area: diagnostics
priority: medium
owner: Diagnostics maintenance
parent: null
blocked_by: []
spec_mode: required
openspec_change: transfer-volume-comparison
created: 2026-11-22
updated: 2026-11-22
---

## Goal

Compare a short and a long HTTPS download in one manual RAW scan.

## Acceptance criteria

- Use one HTTPS origin, pinned IP and port, and a shared deadline.
- Check each complete response against its own size and SHA-256.
- Report bytes and body timing for each result, including partial transfers.

## Worktree ownership

- Native engine, Rust contracts, loopback tests, and task specification: integration worktree.
- Android admission, manual form, report rows, catalog, and JVM tests: isolated Android worktree.
