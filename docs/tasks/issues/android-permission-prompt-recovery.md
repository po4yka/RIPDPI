---
id: AND-1795489914228572
title: Correct Android permission prompts and recovery
kind: bug
status: done
area: android
priority: high
owner: Android maintainer
parent: null
blocked_by: []
spec_mode: required
openspec_change: correct-android-permission-prompts
created: 2026-11-24
updated: 2026-11-24
closed_at: "2026-11-24T17:21:21Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Permission and scanner recovery JVM tests, recorded Roborazzi comparisons, and source gates passed. System dialogs on physical devices are outside the approved app scope. The scoped hosted checks passed in run 45441455734.
---

## Goal

Make Android permission and settings requests clear and recoverable.

## Acceptance criteria

- Explain VPN, detection, update, boot, and optional runtime access before Android opens its UI.
- Continue the original action after a grant and keep unrelated features available after denial.
- Run focused app and detection tests, screenshot comparisons, and repository source gates.

## Ownership

- Android maintainer: VPN, notifications, local network, camera, locale resources, and integration.
- Detection worker: detection permission Kotlin and tests.
- Settings worker: update and boot settings Kotlin and tests.
