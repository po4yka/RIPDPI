---
id: DGN-1795345317475114
title: Select the latest completed diagnostics session for export
kind: bug
status: review
area: diagnostics
priority: high
owner: Diagnostics maintenance
parent: null
blocked_by: []
spec_mode: required
openspec_change: latest-completed-diagnostics-export
created: 2026-11-22
updated: 2026-11-22
---

## Goal

Use the last completed diagnostic scan in an automatic archive and keep its available evidence.

## Acceptance criteria

- Select across retained scan history by completed status and completion time.
- Export saved probe results when the full report is too large to retain. Record the unavailable report in the archive.
- Use live-state artifacts when no completed scan is available.
