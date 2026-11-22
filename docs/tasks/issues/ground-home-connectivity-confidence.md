---
id: DGN-1795348431215897
title: Ground Home connectivity confidence in paired measurements
kind: feature
status: review
area: diagnostics
priority: high
owner: Diagnostics maintenance
parent: null
blocked_by: []
spec_mode: required
openspec_change: home-connectivity-confidence
created: 2026-11-22
updated: 2026-11-22
---

## Goal

Show the strength and reach of the path comparison in Home diagnostics.

## Acceptance criteria

- Give a single direct failure with one control and no paired result low confidence.
- Match target settings and measured stages across completed reports.
- Show HTTP recovery on the active path only when the corresponding direct HTTP check failed.
- Preserve report coverage and comparison status in the local result and the redacted archive.
