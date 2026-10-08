---
id: DGN-1791484460786370
title: Keep Home measurements within the original network scope
kind: bug
status: doing
area: diagnostics
priority: medium
owner: home-scope
parent: null
blocked_by: []
spec_mode: required
openspec_change: home-measurement-scope
created: 2026-10-08
updated: 2026-10-08
---

## Goal

Keep Home network measurements and recommendations within the network epoch captured when the run starts.

## Acceptance criteria

- Reject network augmentations and recommendation authority when the epoch or fingerprint is missing or changes, including A-B-A.
- Preserve local routing and detector catalog evidence.
- Cover a change during DNS augmentation and stable-network results with tests.

## Ownership

The home-scope writer owns HomeCompositeOutcomeFinalizer, Home run service scope plumbing, DefaultHomeAnalysisAugmentationSource, and related tests. Other agents own metadata and evidence-control changes. No wire, schema, locale, golden, or lockfile edits. The integration writer owns the generated board and combined validation.
