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

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `cache-transport-policy-per-network-and-host-tuple`
- **Verify:** `just test-module core:diagnostics`
- **Scope (only modify these + this file + the ledger):** `core/diagnostics/**`, `native/rust/crates/ripdpi-failure-classifier/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

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
