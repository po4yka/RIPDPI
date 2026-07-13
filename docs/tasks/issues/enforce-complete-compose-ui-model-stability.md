---
title: Enforce complete Compose UI model stability
type: task
status: doing
area: testing
priority: medium
owner: Codex Compose lane
parent: epic-close-remaining-android-audit-risks
blocks: []
blocked_by: []
created: 2026-07-13
updated: 2026-07-13
---

## Goal

Make the stability gate discover UI state models regardless of existing annotations and reject ordinary mutable collection interfaces.

## Acceptance criteria

- [ ] The gate detects unannotated state models such as Xray import state.
- [ ] Every discovered UI model either uses stable immutable collections or has an explicit justified exclusion.
