---
title: Epic - Multi-hop proxy chains
type: epic
status: backlog
area: epic
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-29
updated: 2026-05-29
---

- [ ] #task Epic - Multi-hop proxy chains #repo/RIPDPI #area/epic #status/backlog 🔼

## Goal

Generalize `chain_relay` from a fixed two-hop (entry → exit) composition to an ordered list of N hops with a sane cap, so users can build layered tunnels of arbitrary (bounded) length.

## Why now

The `chain_relay` relay kind is currently hardcoded to exactly two hops: `RelayChainSection` in `core/engine-api/.../core/RelayNativeConfig.kt` carries only `chainEntry` and `chainExit`, the README describes it as a "two-hop client relay", and `ChainRelayTrustDomainResolver` / `UpstreamRelaySupervisor` model exactly `chainEntryProfileId` / `chainExitProfileId`. Simpler clients (e.g. xivpn) already support arbitrary-length proxy chains for layered-privacy and resilience use cases. Because the two-hop machinery already exists, this is primarily a generalization (make the hop count variable), not new protocol work.

## Key decisions

- **Ordered hop list, bounded.** Replace `chainEntry`/`chainExit` with an ordered `List<ResolvedChainRelayHopConfig>` (min 2, max N). Cap N for latency/stability — proposed `N = 4`; the cap is a typed validation error, not a silent truncation.
- **Composition stays native.** Kotlin owns CRUD / serialization / ordering; the relay-core folds the chain over the ordered hop list in Rust.
- **Per-hop protect + trust.** Every hop's outbound socket honors the `VpnService.protect()` invariant; trust-domain resolution and the anonymity/latency caveat are computed per hop and cumulatively.
- **Backward-compatible migration.** Existing two-hop configs migrate to a 2-element list; config schema version is bumped with a documented migration (current schema version `6`).
- **No auto-composition.** Manual chain authoring only; automatic member selection is out of scope.

## Scope

- **In scope:** ordered N-hop model + validation + migration; native chain composition over N hops; per-hop trust-domain resolution and caveat surfacing; chain editor UI (add/remove/reorder); README relay-matrix update across all 7 locales.
- **Out of scope:** unbounded chains; auto-selecting chain members; new relay protocols (covered by `epic-extended-outbound-protocol-support`).

## Ship definition

- [ ] A chain profile can reference 2..N hops in an explicit order; the cap is enforced with a typed error.
- [ ] Existing two-hop chain configs migrate cleanly to the new list model (round-trip test).
- [ ] Relay-core starts, runs, and stops 2-, 3-, and 4-hop chains; shutdown joins bounded handler work like other relays.
- [ ] Every hop's non-loopback outbound socket is `protect_socket(fd)`-wrapped (verified by the protect audit).
- [ ] Per-hop and cumulative latency / trust caveat is surfaced in the UI.
- [ ] Chain editor supports add / remove / drag-reorder; RDS tokens only; all 7 locales.
- [ ] README relay matrix no longer says "two-hop"; selector-block parity passes across all 7 READMEs.

## Child tasks

**Data and schema**
- [[Generalize chain relay to N hops model and migration]]

**Runtime**
- [[Add N-hop native chain composition]]

**UI**
- [[Add multi-hop chain editor screen]]

## Dependencies

- Depends on: Epic - Subscription and profile import — chain hops reference relay profiles / groups.
- Feeds: [[Epic - Settings backup and restore]] — chain definitions are part of the backup schema.

## Risks / open questions

- Latency and failure probability compound per hop — hence the cap and the cumulative caveat.
- Loop/protect risk multiplies: every hop opens outbound sockets; the `.claude/rules/vpnservice-protect-invariant.md` rule applies to all of them, no exceptions.
- Trust-domain semantics for >2 hops need a clear definition (which hop "owns" the exit identity) before UI copy is finalized.
- Cap value (N) is a product decision; 4 is a starting proposal, validate against real layered-tunnel use cases.

## Links

- [[ripdpi-android]]
- Source of comparison: xivpn (arbitrary-length proxy chains) — adapt the concept, reimplement in Kotlin/Rust.
- Child issues: 3
