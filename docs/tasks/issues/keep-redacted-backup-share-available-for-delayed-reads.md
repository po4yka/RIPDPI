---
id: AND-1790432927304098
title: Keep redacted backup share available for delayed reads
kind: bug
status: review
area: android
priority: high
owner: Android app
parent: null
blocked_by: []
spec_mode: required
openspec_change: preserve-backup-share-for-delayed-read
created: 2026-09-26
updated: 2026-09-26
---

## Goal

Keep a redacted backup readable through its FileProvider URI after the share
chooser returns or the settings route closes. Clean up abandoned cache files
after a bounded retention period.

Ownership: this worktree owns the backup share controller, temporary-file owner,
direct tests, and this task's OpenSpec artifacts. No locale or shared registry
files change.

## Acceptance criteria

- [ ] A recipient can open the shared URI after chooser return and route closure.
- [ ] Failed or unlaunched shares delete partial files immediately.
- [ ] Old share files are pruned while recent shared files stay readable.
- [ ] Focused tests, app unit tests, and app lint pass.
