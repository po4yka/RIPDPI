---
id: AND-1790430169976539
title: Validate numeric IP literals on Android 8
kind: bug
status: review
area: android
priority: medium
owner: App audit
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-09-26
updated: 2026-09-26
spec_reason: regression-tested-single-module
status_detail: API 28 and 29 regression tests passed; API 27 Robolectric startup blocked by androidx.tracing.perfetto fixture initialization.
---

## Goal

On Android API 27 and 28, reject invalid numeric IP input in proxy, relay, WARP, and DNS settings.

## Acceptance criteria

- API 27 and 28 reject hostnames, malformed IPv4, and malformed IPv6 values.
- Valid IPv4 and IPv6 literals remain accepted; wildcard and loopback values remain invalid for direct DNS.
- Targeted `:app` unit tests pass.

## Ownership

- App audit owns `app/src/main/kotlin/com/poyka/ripdpi/utility/ValidateUtils.kt` and its direct tests in the `audit/app-module-20260926` worktree.
- Other audit writers own their own app paths in separate worktrees. No serialized shared files are changed by this task.
