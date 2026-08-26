---
id: EPC-1786264762917282
title: Epic - Transport obfuscation and network-signature research
kind: epic
status: doing
area: epic
priority: medium
owner: Transport obfuscation research lane
parent: null
blocked_by: []
spec_mode: required
openspec_change: epc-1786264762917282-epic-transport-obfuscation-research
created: 2026-06-10
updated: 2026-08-26
source_wiki_pages: []
linked_task: null
---

## Goal

Hold the speculative network-behavior R&D — traffic-shape obfuscation, alternative bootstrap channels, and empirical network-signature measurement — in one place so it is visibly *research*, not committed delivery. These tasks share the property that they need field measurement or a design spike before they can become implementation tasks, and several are gated on external RU-ISP vantage access.

## Why now

These items were scattered as parentless backlog tasks, making them look like ready-to-build features when they are actually pre-implementation investigations. Grouping them sets the right expectation (each needs a spike or field data first) and keeps the active transport epics (`extended-outbound`, `tls-handshake-hardening`) focused on committed work.

## Key decisions

- **Research-first, not delivery.** Each child must produce a design note or a field-measurement matrix before it graduates to an implementation task in another epic.
- **External-vantage gating is explicit.** Tasks needing live RU-ISP measurement (operator signature catalog) cannot progress from the repo alone; that dependency is recorded, not hidden.
- **Drop on weak payoff.** Speculative techniques with no unique advantage over existing cover protocols are dropped, not parked indefinitely (the Marionette-style format-transforming-encryption task was dropped on 2026-06-10 for exactly this reason — ML-detectable, zero implementation, no payoff over existing transports).

## Scope

- **In scope:** constant-rate traffic shaping with a VoIP camouflage profile, DNS-Morph bootstrap as a fallback channel, and empirical operator protocol-class signature measurement feeding `ripdpi-runtime-policy` defaults.
- **Out of scope:** committed transport implementations (extended-outbound epic), TLS handshake hardening (its own epic), and anything with a concrete ship date.

## Child tasks

- [[add-constant-rate-traffic-shaping-voip-camouflage]] — constant-rate shaping profile spike. **Spike done 2026-06-11 — CONDITIONAL-GO** (forward-only QUIC-datagram pacer; gated on a missing low-power hook; stream-wrapper/bidirectional scope dropped).
- [[spike-dns-morph-bootstrap-fallback-channel]] — DNS-Morph alternative bootstrap channel. **Spike done 2026-06-11 — EXTERNALLY-GATED** (sound + not a duplicate, but unverifiable without an RU-reachable bridge + an outbound-:53 reachability measurement).
- [[investigate-operator-protocol-class-signatures]] — empirical block-rate matrix across transports; gated on ≥3 RU ISP vantages (external). **Spike done 2026-06-11 — CONDITIONAL-GO on methodology** (field run stays gated; key finding: no policy type is keyed by transport-crate identity, so lock the `(transport-class, scope-hash)` matrix schema now).

## Ship definition

- [x] Each child has produced either a design note or a field-measurement matrix and a go/no-go recommendation. — All three design notes + go/no-go verdicts landed 2026-06-11 (in each child's task file). (The operator-signature child's *field-measurement matrix* itself stays externally gated on ≥3 RU vantages; the design note + methodology is its spike deliverable.)
- [ ] Any technique that graduates is re-filed as an implementation task under the appropriate transport/TLS epic. — Graduation targets + minimal first slices are recorded in each child; re-filing happens when each gate clears (none is unconditionally ready yet: shaping needs a low-power hook first, DNS-Morph + operator-signature field run are externally gated).
- [ ] Dropped techniques are recorded with the reason (no silent abandonment). — None dropped this pass (the Marionette drop on 2026-06-10 stands).

## Status

**2026-06-11 — design-spike pass complete (3/3 children).** Ran three parallel repo-grounded design spikes; no production code merged (per the design-spikes-only scope). Verdicts:

| Child | Verdict | One-line |
| --- | --- | --- |
| Constant-rate VoIP-camouflage shaping | **conditional-go** | Real, but only on the QUIC-datagram surface (not the stream wrapper the criteria assumed), gated on a low-power hook that does not exist yet; bidirectional padding needs server cooperation we don't have. |
| DNS-Morph bootstrap fallback | **externally-gated** | Not a duplicate of `ripdpi-dns-resolver`; trivially buildable; protect already covered — but its whole payoff (a measured outbound-:53 middlebox behavior gap) is unverifiable without an RU-reachable bridge + an in-transit :53 measurement. |
| Operator protocol-class signatures | **conditional-go (methodology)** | Methodology + policy-hook design are sound; field run stays externally gated on ≥3 RU vantages. Load-bearing finding: no policy type is keyed by transport-crate identity, so the matrix must carry `(transport-class, scope-hash)` keying from day one. |

Frontmatter rollup: traffic-shaping → `backlog` (spike resolved; implementation parked behind its low-power-hook prerequisite — file kept to hold the note); DNS-Morph → `blocked` (externally gated); operator signatures → `blocked` (field run gated, methodology delivered). The epic stays `backlog` as the holding home for the gated/graduating follow-ups. (No child is `done`/`dropped`, so no task file is deleted — the design notes live in the task files per the spike scope.)

## Risks / open questions

- Field-measurement children depend on sustained external RU-ISP access that the repo cannot provide.
- Research scope creep — keep each child a bounded spike, not an open-ended investigation.

## References

- `desync-engine` skill, `operator-protocol-class-blocking-shift-dec-2025` wiki page.
- Dropped 2026-06-10: `add-format-transforming-encryption-marionette-style-protocol-shapeshift` (ML-detectable, no payoff).
