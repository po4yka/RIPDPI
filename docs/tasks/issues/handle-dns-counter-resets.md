---
title: Handle DNS telemetry counter resets
type: task
status: doing
area: dns
priority: medium
owner: Codex service lane
parent: epic-close-remaining-android-audit-risks
blocks: []
blocked_by: []
created: 2026-07-13
updated: 2026-07-13
---

## Goal

Reset the DNS failover baseline when native cumulative counters roll back after a tunnel rebuild without a DNS signature change.

## Acceptance criteria

- [ ] A `100/10 -> 0/0` sequence with the same signature establishes a new baseline.
- [ ] New failures after reset trigger the existing failover thresholds.
