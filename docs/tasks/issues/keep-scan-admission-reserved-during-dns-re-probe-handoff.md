---
id: DGN-1790433094681403
title: Keep scan admission reserved during DNS re-probe handoff
kind: bug
status: review
area: diagnostics
priority: high
owner: diagnostics lifecycle writer
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-dns-reprobe-admission-handoff
created: 2026-09-26
updated: 2026-09-26
---

## Goal

Keep scan admission reserved while an automatic RAW_PATH scan hands off to a DNS-corrected hidden IN_PATH re-probe.

## Acceptance criteria

- Manual and automatic starts cannot be admitted while re-probe preparation is suspended or while the hidden re-probe is active.
- The primary bridge is cleaned up after the hidden re-probe reserves its slot, including preparation or registration failure and cancellation paths.
- Focused concurrency regression and diagnostics module tests pass.

## Ownership

- This writer owns `core/diagnostics` scan execution coordination and its tests in an isolated worktree.
- Other writers own export, `dpi`, `dpich`, and `rkn`. No serialized shared file is changed.
