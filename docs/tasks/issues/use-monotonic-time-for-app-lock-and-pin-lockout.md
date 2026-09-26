---
id: AND-1790430239664191
title: Use monotonic time for app lock and PIN lockout
kind: bug
status: review
area: android
priority: high
owner: Android app
parent: null
blocked_by: []
spec_mode: required
openspec_change: fix-app-lock-clock-skew
created: 2026-09-26
updated: 2026-09-26
---

## Goal

Keep PIN lockout and automatic app relock effective when device wall time changes.

Ownership: this worktree owns `AppLockLifecycleObserver.kt`, `PinLockoutManager.kt`,
their direct tests, and this task's OpenSpec artifacts. No shared registries or
locale files are changed.

## Acceptance criteria

- [ ] Moving the device clock forward does not end an active PIN lockout.
- [ ] Moving the device clock backward does not defer automatic app relock.
- [ ] A reboot cannot bypass either guard; unavailable boot identity fails closed.
- [ ] Focused tests, app unit tests, and static analysis pass.
