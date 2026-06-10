---
title: "Epic - Transport obfuscation and censor-signature research"
type: epic
status: backlog
area: epic
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-06-10
updated: 2026-06-10
source_wiki_pages: []
linked_task: null
---

## Goal

Hold the speculative anti-censorship R&D — traffic-shape obfuscation, alternative bootstrap channels, and empirical censor-signature measurement — in one place so it is visibly *research*, not committed delivery. These tasks share the property that they need field measurement or a design spike before they can become implementation tasks, and several are gated on external RU-ISP vantage access.

## Why now

These items were scattered as parentless backlog tasks, making them look like ready-to-build features when they are actually pre-implementation investigations. Grouping them sets the right expectation (each needs a spike or field data first) and keeps the active transport epics (`extended-outbound`, `tls-handshake-hardening`) focused on committed work.

## Key decisions

- **Research-first, not delivery.** Each child must produce a design note or a field-measurement matrix before it graduates to an implementation task in another epic.
- **External-vantage gating is explicit.** Tasks needing live RU-ISP measurement (RKN signature catalog) cannot progress from the repo alone; that dependency is recorded, not hidden.
- **Drop on weak payoff.** Speculative techniques with no unique advantage over existing cover protocols are dropped, not parked indefinitely (the Marionette-style format-transforming-encryption task was dropped on 2026-06-10 for exactly this reason — ML-detectable, zero implementation, no payoff over existing transports).

## Scope

- **In scope:** constant-rate traffic shaping with a VoIP camouflage profile, DNS-Morph bootstrap as a fallback channel, and empirical RKN protocol-class signature measurement feeding `ripdpi-runtime-policy` defaults.
- **Out of scope:** committed transport implementations (extended-outbound epic), TLS handshake hardening (its own epic), and anything with a concrete ship date.

## Child tasks

- [[add-constant-rate-traffic-shaping-voip-camouflage]] — constant-rate shaping profile spike.
- [[spike-dns-morph-bootstrap-fallback-channel]] — DNS-Morph alternative bootstrap channel.
- [[investigate-rkn-unannounced-protocol-class-signatures]] — empirical block-rate matrix across transports; gated on ≥3 RU ISP vantages (external).

## Ship definition

- [ ] Each child has produced either a design note or a field-measurement matrix and a go/no-go recommendation.
- [ ] Any technique that graduates is re-filed as an implementation task under the appropriate transport/TLS epic.
- [ ] Dropped techniques are recorded with the reason (no silent abandonment).

## Risks / open questions

- Field-measurement children depend on sustained external RU-ISP access that the repo cannot provide.
- Research scope creep — keep each child a bounded spike, not an open-ended investigation.

## References

- `desync-engine` skill, `rkn-protocol-class-blocking-shift-dec-2025` wiki page.
- Dropped 2026-06-10: `add-format-transforming-encryption-marionette-style-protocol-shapeshift` (ML-detectable, no payoff).
