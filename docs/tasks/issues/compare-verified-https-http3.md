---
id: DGN-1795357427018830
title: Compare verified HTTPS and HTTP/3 transfers
kind: feature
status: review
area: diagnostics
priority: medium
owner: Diagnostics maintenance
parent: null
blocked_by: []
spec_mode: required
openspec_change: http3-transfer-comparison
created: 2026-11-22
updated: 2026-11-22
---

## Goal

Run a manual RAW comparison of one verified HTTPS object over TCP and HTTP/3.

## Acceptance criteria

- Pin both requests to one IP and port.
- Bound the pair by one deadline and verify size and SHA-256 per response.
- Show the first differing stage and received bytes for each leg.

## Worktree ownership

- Native contracts, runner, and OpenSpec: integration worktree.
- Android input, report presentation, and tests: isolated Android worktree.
- Do not edit the board, shared specification, or native files in the Android worktree.
