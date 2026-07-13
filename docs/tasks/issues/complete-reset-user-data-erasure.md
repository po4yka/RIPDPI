---
title: "Complete reset user data erasure"
type: task
status: todo
area: data
priority: critical
owner: Codex
parent: epic-fix-android-critical-residual-risks
blocks: []
blocked_by: []
created: 2026-07-13
updated: 2026-07-13
---

## Goal

Delete TLS keylogs, probe cache, crash and log files, and all user-owned preference stores during reset.

## Acceptance criteria

- A seeded reset test covers files and `pin_lockout`, `app_lock`, `detection_check_prefs`, and `backup_share_prefs`.
- Reset leaves no listed user artifact behind.
- Focused app reset tests pass.
