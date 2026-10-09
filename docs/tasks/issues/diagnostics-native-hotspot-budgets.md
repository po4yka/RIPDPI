---
id: DGN-1791535329340881
title: Restore diagnostics native hotspot budgets
kind: chore
status: done
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
status_detail: Native refactor is on main and origin/main. Local tests, Clippy, unchanged budgets, contracts, review, and CI run 37909536787 passed.
closed_at: "2026-10-09T10:28:46Z"
closed_reason: All acceptance criteria and required evidence passed.
evidence_summary: Commit c2baf9931 on main and origin/main. 271 monitor-engine tests passed; two opt-in soak tests did not run. Clippy, native budgets, contracts, independent review, and structured review passed. CI run 37909536787 completed with success.
---

## Goal

Restore the diagnostic native hotspot gate after transfer-progress and IP-family work without a baseline increase.

## Acceptance criteria

- Extract coherent planning and live-transfer responsibilities without behavior changes.
- Native hotspot and architecture contract gates pass with unchanged limits.
- Locked monitor-engine tests and Clippy pass. Review confirms the mechanical boundaries.
