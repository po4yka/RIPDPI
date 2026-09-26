---
id: DGN-1790432046570971
title: Preserve manual scan options across hidden probe conflicts
kind: bug
status: review
area: diagnostics
priority: high
owner: diagnostics lifecycle writer
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-hidden-probe-conflict-snapshot
created: 2026-09-26
updated: 2026-09-26
---

## Goal

Keep the original manual scan options when a hidden automatic probe forces conflict resolution before startup.

## Acceptance criteria

- Both WAIT and CANCEL_AND_RUN start with the original deadline, candidate limit, target overrides, owner, and raw-path resume policy.
- The focused controller regression test and diagnostics module tests pass.

## Ownership

- This writer owns `core/diagnostics` scan controller and its tests in an isolated worktree.
- Other writers own export, `dpi`, `dpich`, and `rkn`. No serialized shared file is changed.
