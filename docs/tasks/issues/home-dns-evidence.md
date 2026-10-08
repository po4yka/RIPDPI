---
id: DGN-1791481936635788
title: Require evidence for Home DNS interference claims
kind: bug
status: review
area: diagnostics
priority: medium
owner: Home evidence agent
parent: null
blocked_by: []
spec_mode: required
openspec_change: home-dns-evidence
created: 2026-10-08
updated: 2026-10-08
---

## Goal

Preserve DNS uncertainty in Home and block-layer diagnosis.

Ownership: Home evidence agent owns app augmentation, HomeOutcomeSynthesizer, BlockLayerDiagnosis, and related tests. No shared schemas or resources.

## Acceptance criteria

Different CDN answers and DNS failures remain inconclusive; regression tests and staticAnalysis pass.
