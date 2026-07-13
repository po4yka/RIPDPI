---
title: Protect debug automation receiver
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

Require a privileged permission for exported debug automation that can reset settings, history, or service state.

## Acceptance criteria

- [ ] The merged debug manifest protects every automation entrypoint.
- [ ] A foreign unprivileged caller is rejected by a manifest regression test.
