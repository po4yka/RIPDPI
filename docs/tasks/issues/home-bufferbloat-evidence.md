---
id: DGN-1791482073167643
title: Require overlapping transfer for bufferbloat grades
kind: bug
status: review
area: diagnostics
priority: medium
owner: Home evidence agent
parent: null
blocked_by: []
spec_mode: required
openspec_change: home-bufferbloat-evidence
created: 2026-10-08
updated: 2026-10-08
---

## Goal

Require verified concurrent load for bufferbloat grades.

Ownership: Home evidence agent owns augmentation load probe and related helpers/tests; no shared schemas.

## Acceptance criteria

Failed, empty, and nonoverlapping transfer produces UNKNOWN; overlapping success retains measured grade. Tests and staticAnalysis pass.
