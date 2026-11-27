---
id: TRN-1795705033979683
title: Assess TLS-in-TLS classifier exposure of proxy transports
kind: research
status: review
area: transport
priority: low
owner: Transport obfuscation research lane
parent: EPC-1786264762917282
blocked_by: []
spec_mode: not-required
openspec_change: null
created: 2026-11-26
updated: 2026-11-27
spec_reason: research-only
status_detail: "Design note: docs/adr/0017-tls-in-tls-classifier-exposure.md. Verdict: conditional-go for a repo-local loopback shape measurement; shaping spike externally gated."
---

## Goal

A design note says which RIPDPI transports expose a TLS-in-TLS pattern to a traffic classifier and whether shaping toward one TLS server is worth a spike.

## Context

An academic preprint dated 2026-11-23 reports that shaping a proxy to mimic one TLS server cut a classifier's detection rate from over 85% to about 3% at a 1% false-positive rate. The data came from a non-Russian ISP. This is abstract-level evidence from one source and is unverified here.

Server counterpart: `po4yka/ripdpi-vpn-deploy#SEC-1795706124070316` studies the same TLS-in-TLS exposure. This repository owns the measurement; the server repository consumes the conclusion for its XHTTP profile.

The epic rule applies: research first, a design note or field matrix before any implementation task, and a drop when the payoff is weak.

## Acceptance criteria

- The note lists each current transport that carries an inner TLS session inside an outer TLS session.
- The note states which parts of the preprint result transfer to RIPDPI, with the single-source and non-Russian data limits.
- The note ends with a go, conditional-go, externally-gated or drop recommendation.

## Ownership

Not assigned yet. Research only. No source changes.
