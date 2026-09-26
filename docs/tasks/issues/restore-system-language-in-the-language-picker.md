---
id: UIX-1790432109043744
title: Restore system language in the language picker
kind: bug
status: review
area: ui
priority: medium
owner: UI audit agent
parent: null
blocked_by: []
spec_mode: required
openspec_change: restore-system-language-picker
created: 2026-09-26
updated: 2026-09-26
---

## Goal

After choosing an app language, the user can return the app to the device language.

## Ownership

The UI audit agent owns `LanguagePickerSheet.kt`, its direct test, and the new
language-picker string in all ten app locale sets. Locale resource edits are a
serialized lane; no other writer should edit them during this task.

## Acceptance criteria

- The picker offers a device-language option in every shipped locale.
- Choosing it clears the app locale override and selects that option when reopened.
- Choosing an explicit language still sets the app locale override.
- A targeted regression test, app unit gate, and app/service locale lint pass.
