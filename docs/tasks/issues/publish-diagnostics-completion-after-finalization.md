---
id: DGN-1790430631972892
title: Publish diagnostics completion after finalization
kind: bug
status: review
area: diagnostics
priority: high
owner: diagnostics finalization writer
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-diagnostics-finalization-terminal
created: 2026-09-26
updated: 2026-09-26
---

## Goal

Publish an in-path diagnostic scan as completed only after report finalization and post-scan persistence succeed. Preserve the raw-path settlement barrier.

## Acceptance criteria

- An in-path post-scan write failure retains the native report and leaves the session failed, never completed.
- A late report does not replace an authoritative manual-conflict cancellation with success.
- A successful in-path scan becomes completed after its post-scan writes finish.
- Raw-path terminal publication still follows its durable settlement receipt.
- The focused diagnostics unit tests pass.

## Ownership

- This writer owns `core/diagnostics` finalization, scan execution coordination, and their tests in an isolated worktree.
- Parallel writers own the export, `dpi`, `dpich`, and `rkn` areas. No shared serialized files are changed here.
