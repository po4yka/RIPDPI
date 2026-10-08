---
id: DGN-1791482086226598
title: Do not infer VPN failure from default halted state
kind: bug
status: review
area: diagnostics
priority: medium
owner: root
parent: null
blocked_by: []
spec_mode: required
openspec_change: require-vpn-failure-evidence
created: 2026-10-08
updated: 2026-10-08
---

## Goal

An offline launch reports network unavailability without inventing a prior VPN session.

## Acceptance criteria

- A halted VPN default does not change the network snapshot evidence.
- Explicit snapshot evidence is preserved.
- Collector regression tests and combined static analysis pass.

## Ownership

Root owns DiagnosticsPlanningServices.kt and collector tests in the diagnostics network guard worktree. Other writers use separate worktrees. No schema, locale, lockfile, baseline, or golden changes.
