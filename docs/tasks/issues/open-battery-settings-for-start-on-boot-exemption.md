---
id: AND-1790434316820665
title: Open battery settings for start-on-boot exemption
kind: bug
status: review
area: android
priority: medium
owner: Android app
parent: null
blocked_by: []
spec_mode: required
openspec_change: use-battery-settings-for-boot-exemption
created: 2026-09-26
updated: 2026-09-26
---

## Goal

When a user enables Start on boot, offer the general battery optimization
settings screen. The current direct exemption action requires an undeclared
permission, so it cannot be used by this app.

Ownership: this worktree owns `StartOnBootController.kt`, its direct test, and
this task's OpenSpec artifacts. No permission or locale changes.

## Acceptance criteria

- [ ] No start-on-boot candidate uses the direct exemption action.
- [ ] General battery settings and app details remain fallback candidates.
- [ ] The existing Start on boot opt-in still emits the guidance effect.
- [ ] Focused tests and app lint pass.
