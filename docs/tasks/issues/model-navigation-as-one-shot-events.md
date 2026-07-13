---
title: Model navigation as one-shot events
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

Replace replayable boolean navigation/completion flags with explicit one-shot event delivery.

## Acceptance criteria

- [ ] Rotation or collector restart cannot repeat completed navigation.
- [ ] An event emitted during a temporary collector gap is delivered once to the single UI consumer.
