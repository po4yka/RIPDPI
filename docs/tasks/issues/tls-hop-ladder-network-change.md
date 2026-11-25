---
id: DGN-1795610822069114
title: Detect network changes that keep the local address in the TLS hop ladder
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

`native/rust/crates/ripdpi-diagnostics-tls/src/tls/hop_ladder.rs#SocketLadderProbe` uses a new local address as its only network-change signal. A switch that keeps the address is not seen.

## Acceptance criteria

The probe uses the Android network handle when the engine passes one, or the limit is recorded in an ADR. A test covers a change with the same local address.
