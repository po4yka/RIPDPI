---
title: Define TransportPolicy struct and per-host state
type: task
status: backlog
area: diagnostics
priority: high
owner: unassigned
parent: epic-direct-mode-transport-policy-and-verdicts
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-15
---

- [ ] #task Define TransportPolicy struct and per-host state #repo/RIPDPI #area/diagnostics #status/backlog ⏫

## Goal contract

<!-- goal-contract:auto -->
- **Ledger key:** `define-transportpolicy-struct-and-per-host-state`
- **Verify:** `just test-module core:diagnostics`
- **Scope (only modify these + this file + the ledger):** `core/diagnostics/**`, `native/rust/crates/ripdpi-runtime-policy/**`
- **Blocked-by (must be DONE in the ledger first):** _none_
- **On completion:** run **Verify**; paste its full output + exit code into the transcript; set this file's canonical `- [ ] #task` line to `[x]` and `#status/done` on pass (or `#status/blocked` + a one-line reason on fail); update this task's row in `docs/tasks/GOAL_LEDGER.md` (Status = DONE/BLOCKED, Proof = the Verify command + exit code); then `cat docs/tasks/GOAL_LEDGER.md` so the ledger state is in the transcript.
<!-- /goal-contract:auto -->

## Summary

Introduce the `TransportPolicy` type the rest of the direct-mode system uses as its per-host source of truth.

```text
TransportPolicy {
quic_mode: ALLOW | SOFT_DISABLE | HARD_DISABLE
preferred_stack: H3 | H2 | H1
dns_mode: SYSTEM | DOH_PRIMARY | DOH_SECONDARY
tcp_family: NONE | SEG_PRE_SNI | SEG_MID_SNI | REC_PRE_SNI | REC_MID_SNI
outcome: TRANSPARENT_OK | OWNED_STACK_ONLY | NO_DIRECT_SOLUTION
}
```

## Plan reference

[[ripdpi-android-direct-mode-plan-2026-04-20]] §3.

## Acceptance criteria

- [ ] Type exists with the fields above; enums are sealed.
- [ ] A default policy constructor used on first contact with an unknown host.
- [ ] Serialization/deserialization is stable across app updates (versioned envelope).
- [ ] Unit tests cover state transitions the rest of the engine drives.

## Links

- [[Epic - Direct-mode transport policy and verdicts]]
- [[ripdpi-android-direct-mode-plan-2026-04-20]]
