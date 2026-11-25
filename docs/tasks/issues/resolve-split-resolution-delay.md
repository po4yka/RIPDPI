---
id: TRN-1795610836723023
title: Add the RFC 8305 Resolution Delay to resolve_split
kind: chore
status: backlog
area: transport
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

`native/rust/crates/ripdpi-diagnostics-transport/src/transport/address.rs#resolve_split` has no Resolution Delay, so a stalled address family costs the whole budget.

## Acceptance criteria

`resolve_split` waits at most the RFC 8305 Resolution Delay (50 ms) for the second family after the first answer. A paused-time test proves the bound.
