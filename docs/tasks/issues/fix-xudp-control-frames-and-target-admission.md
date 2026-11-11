---
id: TRN-1794371393565180
title: Fix XUDP control frames and target admission
kind: bug
status: doing
area: transport
priority: high
owner: Transport maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-xudp-control-and-target-admission
created: 2026-11-11
updated: 2026-11-11
---

## Goal

Repair TRN-03 F1 and F2 while retaining Single XUDP scope and resource limits.

## Acceptance criteria

Valid control frames preserve the next datagram. Invalid targets return InvalidInput before enqueue. Regression tests, pinned-peer loopback tests, source gates, and independent review pass.

## Ownership

Root owns xudp.rs and planning records. Contract/review agents are read-only. The target-validation worker owns its F2 changes in a separate worktree, while root owns F1 here. Both edit distinct decoder/target ranges; root integrates F2 after committing F1. No writer shares a physical file. Dependency, locale, schema, golden, and baseline lanes are unchanged.
