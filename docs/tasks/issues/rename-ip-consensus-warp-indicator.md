---
id: DGN-1795610467280740
title: Rename IpConsensus warpIndicator to an ASN-difference name
kind: chore
status: backlog
area: diagnostics
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

`core/detection` `IpConsensusBuilder.kt` exposes `warpIndicator`. A deleted KDoc said that it is a legacy name for an ASN difference and does not prove WARP or an underlay route.

## Acceptance criteria

The field has a name that says ASN difference, all callers and serialized forms are updated, and no UI text claims WARP from this field.
