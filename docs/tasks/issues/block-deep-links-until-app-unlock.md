---
id: AND-1790430183226560
title: Block deep links until app unlock
kind: bug
status: review
area: android
priority: high
owner: Android UI
parent: null
blocked_by: []
spec_mode: required
openspec_change: and-1790430183226560-block-deep-links-until-app-unlock
created: 2026-09-26
updated: 2026-09-26
---

## Goal

Keep an app with onboarding or local authentication enabled behind its gate when a deep link opens it. After authentication, open the requested destination.

## Acceptance criteria

- A cold or warm deep link cannot show a destination behind onboarding or the biometric gate.
- Supported deep links open the requested destination after the gate clears.
- Navigation's direct Activity-intent handling cannot bypass the gate.
- Targeted Android unit tests cover these paths.

## Ownership

- Android UI writer: `MainActivity`, `MainActivityShellController`, `RipDpiNavHost`, and their direct tests.
- No serialized shared files or parallel writers are involved.
