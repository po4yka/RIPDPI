---
id: DGN-1794755310955921
title: Correct DIA-19 local proxy and Xray diagnostics
kind: bug
status: review
area: diagnostics
priority: high
owner: Diagnostics integration
parent: null
blocked_by: []
spec_mode: required
openspec_change: correct-dia19-diagnostics
created: 2026-11-15
updated: 2026-11-15
status_detail: Host regressions, source gates and independent review complete.
---

## Goal

Correct the twelve confirmed DIA-19 findings. Make scan settings, local protocol results and report evidence agree.

## Acceptance criteria

- Deliver one implementation commit per finding.
- Exercise settings, scan outcomes, wire framing, cancellation and privacy with local regressions.
- Run source gates and independent review. Record Android acceptance separately.
