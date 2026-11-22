---
id: DGN-1795329243655314
title: Require current selector and direct path evidence
kind: bug
status: review
area: diagnostics
priority: high
owner: Diagnostics
parent: null
blocked_by: []
spec_mode: required
openspec_change: probe-evidence-freshness
created: 2026-11-22
updated: 2026-11-22
---

## Goal

Keep field-report aggregates on the network that produced each probe. Require two distinct fresh failures to learn that a direct path is unavailable.

## Acceptance criteria

- Capture the allowed report label and generation before a probe. Count the probe only if consent and the generation are still current at commit.
- Stable Wi-Fi counts. Wi-Fi to cellular and Wi-Fi to cellular to Wi-Fi do not count the in-flight probe.
- Expired or duplicate direct-path failures cannot confirm or extend a negative policy.

## Ownership

- Field report owns the ledger, selector probe integration, and field-report tests.
- Local learning owns the direct-path learner and its tests.
