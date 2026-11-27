---
id: DGN-1795705002630350
title: Locate the stalling hop of a user-built relay chain with a large transfer
kind: feature
status: review
area: diagnostics
priority: low
owner: Diagnostics engineer
parent: null
blocked_by: []
spec_mode: required
openspec_change: dgn-1795705002630350-locate-the-stalling-hop-of-a-user-built-relay-chain-with-a-large-transfer
created: 2026-11-26
updated: 2026-11-27
status_detail: Merged to main; remote CI evidence recorded (run 46009834608 on d9aa00b99f).
---

## Goal

For a relay chain that the user built on the client, diagnostics shows the first hop where a large transfer stops.

## Context

Evidence is the same chained-client write-up as in the connection test task: messages passed over four hops but images did not, so the stop point was invisible to a small test. It is public, single-source and unverified here.

Scope limit from ADR 0009: a server-side cascade stays client-transparent behind one opaque endpoint, so this task applies only to chains that the client builds. The client does not probe inside a server-side cascade.

Related: RLY-1795610464611997 (chain hops beyond two in the relay wire config) bounds the chain length that the client can express today, and the connection test task supplies the payload and verdict model.

## Acceptance criteria

- For a user-built chain, diagnostics transfers a payload larger than the TCP-freeze threshold through each hop prefix in order and reports the first hop where the volume stops, with the byte count at the stop.
- A server-side cascade is probed as one endpoint, and the report does not claim per-hop results for it.
- The per-hop result reuses the transfer measurement and payload controls of the connection test task.

## Ownership

One writer, Diagnostics engineer, owns this change: the chain-prefix locator in `core/service` (relay chain model is read, not changed), `CandidateRelayPayloadProbe.measureVolume`, the onboarding volume check, and the new onboarding string in all 10 locales.
