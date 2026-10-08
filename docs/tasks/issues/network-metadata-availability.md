---
id: DGN-1791484646270191
title: Keep unavailable network observations unknown
kind: feature
status: doing
area: diagnostics
priority: medium
owner: metadata-worker
parent: null
blocked_by: []
spec_mode: required
openspec_change: network-metadata-availability
created: 2026-10-08
updated: 2026-10-08
---

## Goal

Unavailable network observations remain unknown in UI and export.

## Acceptance criteria

- Legacy and missing capability snapshots show unknown.
- Missing roaming does not become false.
- Ownership: metadata-worker model/provider/factory/mapper and tests; root summaries/resources.
