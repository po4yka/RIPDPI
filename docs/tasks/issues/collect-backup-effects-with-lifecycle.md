---
title: Collect backup effects with lifecycle
type: task
status: doing
area: ui
priority: medium
owner: Codex Compose lane
parent: epic-close-remaining-android-audit-risks
blocks: []
blocked_by: []
created: 2026-07-13
updated: 2026-07-13
---

## Goal

Suspend backup restart, share, and snackbar effect collection while the UI lifecycle is below `STARTED`.

## Acceptance criteria

- [ ] Effects emitted while stopped do not trigger callbacks until collection resumes according to the chosen event contract.
- [ ] Flow/callback replacement and lifecycle restarts do not duplicate effects.
