---
id: DNS-1795610829282002
title: Run the resolver survey with bounded concurrency
kind: chore
status: backlog
area: dns
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

`native/rust/crates/ripdpi-diagnostics-dns/src/resolver_panel.rs#ResolverSurveyRunner` queries one resolver after another. A deleted comment named bounded concurrency (8 in flight) as future work.

## Acceptance criteria

The survey runs at most a fixed number of resolvers at the same time, or the note is dropped by a recorded decision. A test proves the bound.
