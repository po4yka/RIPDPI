---
title: Retain subprocess ownership until exit
type: task
status: doing
area: service
priority: medium
owner: Codex service lane
parent: epic-close-remaining-android-audit-risks
blocks: []
blocked_by: []
created: 2026-07-13
updated: 2026-07-13
---

## Goal

Keep the relay process handle until termination is confirmed and report failure when TERM/KILL or waiting cannot establish exit.

## Acceptance criteria

- [ ] A fake process that ignores TERM and KILL remains owned and yields failure.
- [ ] Interruption cannot silently orphan a process.
