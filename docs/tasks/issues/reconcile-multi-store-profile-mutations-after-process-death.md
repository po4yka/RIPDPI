---
title: Reconcile multi-store profile mutations after process death
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

Add durable recovery for AWG, Relay, and WARP mutations that cross Room, Keystore, metadata, and DataStore boundaries.

## Acceptance criteria

- [ ] A persisted mutation marker or equivalent startup reconciliation closes every kill boundary.
- [ ] Recovery never resurrects deleted profiles or leaves active IDs pointing at missing profiles.
