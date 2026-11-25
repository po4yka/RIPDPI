---
id: DAT-1795610465810324
title: Split the large diagnostics DAOs by table
kind: chore
status: backlog
area: data
priority: low
owner: comment-ratchet
parent: null
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-25
updated: 2026-11-25
spec_reason: mechanical-refactor
---

## Goal

`DiagnosticsNetworkPreferenceDao` and `DiagnosticsRetentionDao` in `core/diagnostics-data` carry many functions. Deleted source comments excused this with "splitting it would be artificial".

## Acceptance criteria

Each DAO owns the queries of one table, or the reason to keep one DAO is recorded in an ADR. Room schema and existing DAO tests stay green.
