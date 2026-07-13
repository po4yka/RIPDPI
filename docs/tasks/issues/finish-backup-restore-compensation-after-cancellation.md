---
title: Finish backup restore compensation after cancellation
type: task
status: doing
area: data
priority: high
owner: Codex data lane
parent: epic-close-remaining-android-audit-risks
blocks: []
blocked_by: []
created: 2026-07-13
updated: 2026-07-13
---

## Goal

Run restore rollback in `NonCancellable` and surface compensation failure as a distinct integrity failure.

## Acceptance criteria

- [ ] Cancellation after mutation still restores the complete snapshot.
- [ ] Rollback failure retains the original failure and is not reported as an ordinary abort.
