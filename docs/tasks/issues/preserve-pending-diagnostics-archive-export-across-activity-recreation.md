---
id: AND-1790432494546766
title: Preserve pending diagnostics archive export across Activity recreation
kind: bug
status: review
area: android
priority: medium
owner: Android UI
parent: null
blocked_by: []
spec_mode: required
openspec_change: preserve-pending-archive-export
created: 2026-09-26
updated: 2026-09-26
---

## Goal

Finish a diagnostics archive export when Android recreates the Activity while the document picker is open.

## Acceptance criteria

- A returned document URI writes the originally requested diagnostics archive after Activity recreation.
- Cancelling the picker creates no archive and leaves no pending export.
- The app unit regression test and app source checks pass.
