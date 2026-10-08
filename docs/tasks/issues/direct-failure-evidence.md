---
id: DGN-1791484568964479
title: Keep generic direct failures unattributed
kind: bug
status: doing
area: diagnostics
priority: medium
owner: controls
parent: null
blocked_by: []
spec_mode: required
openspec_change: direct-failure-evidence
created: 2026-10-08
updated: 2026-10-08
---

## Goal

Keep generic failed attempts unattributed.

## Acceptance criteria

Verdict and capability tests require UNKNOWN_DIRECT_FAILURE and null transport class.

## Ownership

Controls worker owns DirectModePolicySupport.kt and its tests; root owns UI labels.
