---
title: Cache transport policy per network and host tuple
type: task
status: backlog
area: diagnostics
priority: medium
owner: unassigned
parent: epic-direct-mode-transport-policy-and-verdicts
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-20
---

- [ ] #task Cache transport policy per network and host tuple #repo/RIPDPI #area/diagnostics #status/backlog 🔼

## Summary

Persist `TransportPolicy` keyed by `(host, ip set, app family, network
profile)`. Sibling to the TLS family cache. Share the atomic-write path
and the Phase 5 revalidation rules.

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] §3 + "Phase 5 — Persistence
and revalidation".

## Acceptance criteria

- [ ] Cache keyed by the exact tuple.
- [ ] Hit path skips the classification phase.
- [ ] Shares the same invalidation rules as the family cache (ASN change,
    access-type change, 3 consecutive failures, 7-day TTL, HTTPS/SVCB
    TTL expiry, ECH capability change).
- [ ] Write path uses `AtomicFile` (see [[Make cache snapshot writes atomic]]).

## Links

- [[Cache winning family per network and host tuple]]
- [[Persist direct-mode policy with revalidation]]
- [[Make cache snapshot writes atomic]]
- [[Epic - Direct-mode transport policy and verdicts]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]
