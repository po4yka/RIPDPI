---
title: Deliver terminal service failures reliably
type: task
status: doing
area: data
priority: medium
owner: Codex service lane
parent: epic-close-remaining-android-audit-risks
blocks: []
blocked_by: []
created: 2026-07-13
updated: 2026-07-13
---

## Goal

Prevent terminal `ServiceEvent.Failed` events from being evicted by telemetry bursts.

## Acceptance criteria

- [ ] More than 64 preceding events cannot make a failure disappear before its intended consumer handles it.
- [ ] Existing fan-out and ordering requirements remain explicit in tests.
