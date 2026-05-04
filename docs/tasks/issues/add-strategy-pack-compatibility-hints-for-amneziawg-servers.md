---
title: Add strategy-pack compatibility hints for AmneziaWG servers
type: task
status: backlog
area: outbound
priority: low
owner: unassigned
parent: epic-amneziawg-outbound-support
blocks: []
blocked_by: []
created: 2026-04-24
updated: 2026-04-24
---

- [ ] #task Add strategy-pack compatibility hints for AmneziaWG servers #repo/RIPDPI #area/outbound #status/backlog 🔽

## Summary

Teach the strategy-pack metadata schema that AmneziaWG profiles are
"server-coordinated fixed config": the obfuscation params must match
the server exactly, and the strategy learner must not vary them.

## Context

RIPDPI's strategy learner rotates TLS arms, QUIC variants, direct-mode
verdicts, etc. AmneziaWG's obfuscation params are part of the server's
config; varying them client-side would break every handshake. The
learner should treat AWG profiles as opaque and not emit candidate
arms that touch `Jc/Jmin/Jmax/S1–S4/H1–H4/I1–I5`.

## Acceptance criteria

- [ ] Strategy-pack schema (`StrategyPackCatalog`) gains a
    `fixed_config_protocols` field listing protocol types whose
    params must not be varied.
- [ ] `amneziawg` is included in that list in the default pack.
- [ ] Strategy learner / candidate generator honors the field: no
    generated arm mutates an AWG profile's obfuscation params.
- [ ] Runtime selector respects the hint: it still picks between
    AWG profiles within a group, but never rewrites an individual
    AWG profile's params.
- [ ] Documented in `docs/strategy-packs.md` so offline pack authors
    know the constraint.
- [ ] Unit test: an attempt to vary an AWG profile's `Jc` in a
    generated candidate is rejected in the pack-validation pass.

## Links

- [[Epic - AmneziaWG outbound support]]
