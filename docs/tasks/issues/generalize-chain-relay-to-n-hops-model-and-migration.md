---
title: Generalize chain relay to N hops model and migration
type: task
status: done
area: relay
priority: medium
owner: unassigned
parent: epic-multi-hop-proxy-chains
blocks: []
blocked_by: []
created: 2026-05-29
updated: 2026-05-29
---

- [x] #task Generalize chain relay to N hops model and migration #repo/RIPDPI #area/relay #status/done 🔼

## Summary

Replace the fixed `chainEntry` / `chainExit` pair in the chain-relay config model with an ordered, bounded list of hops, and migrate existing two-hop configs without data loss.

## Context

`RelayChainSection` in `core/engine-api/src/main/kotlin/com/poyka/ripdpi/core/RelayNativeConfig.kt` currently has exactly two fields (`chainEntry: ResolvedChainRelayHopConfig?`, `chainExit: ResolvedChainRelayHopConfig?`). `ChainRelayTrustDomainResolverTest` and `UpstreamRelaySupervisorTest` assert on `chainEntryProfileId` / `chainExitProfileId`. To support N hops the model must become an ordered list, and the config schema (currently version `6`) must migrate the two-hop shape forward.

## Acceptance criteria

- [ ] `RelayChainSection` exposes an ordered `List<ResolvedChainRelayHopConfig>` (min 2, max N; proposed N=4) replacing the entry/exit pair.
- [ ] Out-of-range hop counts fail with a typed validation error (no silent truncation).
- [ ] Config schema version is bumped; a migration converts existing `{chainEntry, chainExit}` configs to a 2-element list.
- [ ] Round-trip serialization test (old config → migrated → serialized) passes; `CONFIG_CONTRACTS.md` updated.
- [ ] `ChainRelayTrustDomainResolver` and the supervisor compile against the list model (resolver behavior covered by [[Add N-hop native chain composition]]).
- [ ] Any changed golden carries the intentional-change rationale per `.claude/rules/golden-bless-discipline.md`.

## Source references

**Reference (xivpn):** arbitrary-length proxy chain composition — concept only; xivpn is Java/Xray and is not copied.

**Adapt:** the existing two-hop `ResolvedChainRelayHopConfig` shape, extended to an ordered list.

**Invent:** the bounded-list validation, the schema migration from the entry/exit pair, and the N cap.

## Links

- [[Epic - Multi-hop proxy chains]]
