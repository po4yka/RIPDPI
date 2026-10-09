---
id: DGN-1791535329340881
title: Restore diagnostics native hotspot budgets
kind: chore
status: doing
area: diagnostics
priority: high
owner: native-hotspot
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-10-09
updated: 2026-10-09
spec_reason: mechanical-refactor
---

## Goal

Restore the diagnostic native hotspot gate after transfer-progress and IP-family work without a baseline increase.

## Acceptance criteria

- Extract coherent planning and live-transfer responsibilities without behavior changes.
- Native hotspot and architecture contract gates pass with unchanged limits.
- Locked monitor-engine tests and Clippy pass. Review confirms the mechanical boundaries.
