---
id: AND-1790433472437083
title: Keep locked launch requests through Activity recreation
kind: bug
status: review
area: android
priority: medium
owner: Android UI
parent: null
blocked_by: []
spec_mode: required
openspec_change: keep-locked-launch-requests-after-recreation
created: 2026-09-26
updated: 2026-09-26
---

## Goal

Keep deferred launch and relock requests across Activity recreation while the app is locked.

## Acceptance criteria

- A public deep link, shared diagnostics link, and import route still open after recreation and unlock.
- A pending relock still takes the app to the biometric screen after recreation.
- App unit tests and source checks pass.
