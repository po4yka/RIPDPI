---
id: DAT-1795085420746769
title: Consume ripdpi-bundle v3 and feed endpoint health into failover
kind: feature
status: review
area: data
priority: high
owner: Android data engineer
parent: EPC-1795085162356362
blocked_by: [DGN-1795085357320979]
spec_mode: required
openspec_change: dat-1795085420746769-consume-bundle-v3-endpoint-health
created: 2026-11-19
updated: 2026-11-19
status_detail: Review findings fixed with regression tests; owner Gradle run pending
---

## Goal

Parse ripdpi-bundle v3, reject v2, and use endpoint health in failover ordering (down excluded, degraded deprioritised, unknown and expired neutral). Counterparts: deploy D1, D4.

## Acceptance criteria

- Golden tests cover v3 parse, v2 rejection, ordering, and expiry.
- Drift pin matches the vendored schema.
- Blocked by R2 (DGN-1795085357320979).
