---
id: TST-1786272277266667
title: Calibrate emulator strategy evidence against privacy-clean field failures
kind: research
status: backlog
area: testing
priority: medium
owner: Diagnostics research maintainer
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-08-09
updated: 2026-08-09
spec_reason: research-only
---

## Goal

Measure whether simulator winners predict privacy-clean real-device failures well enough to justify per-family emulator-to-field calibration factors.

## Acceptance criteria

- Define a privacy review and provenance contract that excludes user identifiers, raw browsing history, and uncontrolled sensitive payloads.
- Curate representative real-device failure fixtures for at least the strategy families currently scored by the offline simulator.
- Run the existing calibration command and report confidence intervals, sample limitations, and hold-out agreement rather than treating synthetic self-consistency as field accuracy.
- Land calibration factors only when they improve held-out agreement without regressing deterministic simulator fixtures; otherwise record a no-change research verdict.
