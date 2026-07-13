---
title: Reject external selector mutations
type: task
status: doing
area: android
priority: medium
owner: Codex security lane
parent: epic-close-remaining-android-audit-risks
blocks: []
blocked_by: []
created: 2026-07-13
updated: 2026-07-13
---

## Goal

Prevent foreign intents delivered to exported `MainActivity` from changing the selected group or profile.

## Acceptance criteria

- [ ] Forged explicit launch and `onNewIntent` inputs cannot call selector mutation APIs.
- [ ] Trusted in-app navigation retains selector behavior.
