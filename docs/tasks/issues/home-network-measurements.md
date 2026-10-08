---
id: DGN-1791482030251870
title: Measure IPv6 reachability and report resolver identity
kind: bug
status: review
area: diagnostics
priority: medium
owner: Home evidence agent
parent: null
blocked_by: []
spec_mode: required
openspec_change: home-network-measurements
created: 2026-10-08
updated: 2026-10-08
---

## Goal

Report observed IPv6 connection and platform resolver metadata.

Ownership: Home evidence agent owns app augmentation and related tests; no shared schemas.

## Acceptance criteria

Connect success/failure and absent resolver tests pass; staticAnalysis passes.
