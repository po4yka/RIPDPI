---
id: AND-1795489914228572
title: Correct Android permission prompts and recovery
kind: bug
status: doing
area: android
priority: high
owner: Android maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: correct-android-permission-prompts
created: 2026-11-24
updated: 2026-11-24
---

## Goal

Make Android permission and settings requests clear and recoverable.

## Acceptance criteria

- Explain VPN, detection, update, boot, and optional runtime access before Android opens its UI.
- Continue the original action after a grant and keep unrelated features available after denial.
- Run focused tests and repository source gates. Record device evidence when available.

## Ownership

- Android maintainer: VPN, notifications, local network, camera, locale resources, and integration.
- Detection worker: detection permission Kotlin and tests.
- Settings worker: update and boot settings Kotlin and tests.
