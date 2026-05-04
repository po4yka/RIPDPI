---
title: Implement QUIC soft-disable per tuple
type: task
status: todo
area: diagnostics
priority: high
owner: unassigned
parent: epic-direct-mode-transport-policy-and-verdicts
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-04-23
---

- [ ] #task Implement QUIC soft-disable per tuple #repo/RIPDPI #area/diagnostics #status/todo ⏫

## Summary

In transparent mode, drop outbound UDP/443 for the
`(host, ip set, app family, network profile)` tuple when `quic_mode =
SOFT_DISABLE`. Observe whether the app retries on TCP; if it does, win. If
it doesn't, detect and remember (see `NO_TCP_FALLBACK`).

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] §3 policy rule 1 and
SOFT_DISABLE enforcement detail.

## Acceptance criteria

- [x] UDP/443 drop is tuple-scoped — does not affect traffic outside the
    tuple.
- [x] TCP/443 to the same host remains allowed.
- [ ] Hard-disable tightens to the entire host for persistent cases.
- [x] Observability: a counter per tuple for dropped UDP and subsequent
    TCP retries.

## Implementation note

As of 2026-04-23, RIPDPI now enforces tuple-scoped UDP suppression on the
runtime path and keeps TCP allowed for the same authority, with the existing
direct-path learner observing dropped UDP and subsequent TCP retries. The
latest enforcement slice also fixed the contradictory runtime behavior where
`NO_TCP_FALLBACK` lifted UDP suppression but the adaptive UDP/QUIC hint layer
still kept treating QUIC as broken for the same tuple. Remaining follow-up
work is the host-wide `HARD_DISABLE` escalation policy plus the separate
per-app-family invalidation story tracked under
[[Detect NO_TCP_FALLBACK app families]].

## Links

- [[Define TransportPolicy struct and per-host state]]
- [[Detect NO_TCP_FALLBACK app families]]
- [[Epic - Direct-mode transport policy and verdicts]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]
