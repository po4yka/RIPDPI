---
title: Bound boot receiver work
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

Give the `goAsync()` boot lane a bounded deadline and always finish its `PendingResult` inside the broadcast budget.

## Acceptance criteria

- [ ] A never-returning coordinator is cancelled at the deadline.
- [ ] `finish()` runs exactly once on success, failure, timeout, and cancellation.
