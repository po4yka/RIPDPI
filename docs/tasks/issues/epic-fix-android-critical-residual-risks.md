---
title: "Fix Android critical residual risks"
type: epic
status: doing
area: epic
priority: critical
owner: Codex
parent: null
blocks: []
blocked_by: []
created: 2026-07-13
updated: 2026-07-13
---

## Goal

Close all nine High findings from the post-fix Android re-audit with one regression-backed atomic commit per finding.

## Why now

The integrated Android fixes are green, but the re-audit found privacy, privileged-process ownership, VPN teardown, reset, backup, retention, and WARP serialization gaps that remain release blockers.

## Key decisions

- Reproduce every finding with a focused test before changing production behavior.
- Keep each finding in its own Conventional Commit.
- Serialize the nine commits in one worktree and validate the combined tree before fast-forwarding `main`.

## Scope

- `prevent-detection-radio-identifier-upload`
- `unify-root-helper-process-ownership`
- `fail-closed-on-vpn-runtime-stop-failure`
- `stop-vpn-runtime-during-service-destroy`
- `complete-reset-user-data-erasure`
- `make-reset-noncancellable-after-start`
- `serialize-full-backup-snapshots`
- `schedule-diagnostics-retention-without-monitor`
- `serialize-all-warp-profile-mutations`

## Ship definition

All nine child tasks have focused green tests, the combined Android gates pass, nine atomic fix commits are fast-forwarded to `main`, and no unrelated local changes are staged.
