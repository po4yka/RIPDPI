---
title: Define TransportPolicy struct and per-host state
type: task
status: done
area: diagnostics
priority: high
owner: unassigned
parent: epic-direct-mode-transport-policy-and-verdicts
blocks: []
blocked_by: []
created: 2026-04-20
updated: 2026-05-29
---

- [x] #task Define TransportPolicy struct and per-host state #repo/RIPDPI #area/diagnostics #status/done ⏫

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

ripdpi-android-direct-mode-plan-2026-04-20 §3.

## Acceptance criteria

- [x] Type exists with the fields above; enums are sealed.
- [x] A default policy constructor used on first contact with an unknown host.
- [x] Serialization/deserialization is stable across app updates (versioned envelope).
- [x] Unit tests cover state transitions the rest of the engine drives.

## Implementation note

Verified 2026-05-29 against current code:

- Kotlin: `core/data/model/.../TransportPolicy.kt` defines all five fields
  (`quicMode`, `preferredStack`, `dnsMode`, `tcpFamily`, `outcome`) over sealed
  enums, with a permissive default `TransportPolicy()` / `defaultTransportPolicyEnvelope()`
  for first contact and a versioned `TransportPolicyEnvelope`
  (`CurrentTransportPolicyEnvelopeVersion`).
- Rust mirror: `ripdpi-runtime-policy::transport_policy` carries the same shape
  with `TransportPolicy::unknown_host()` and a schema-versioned envelope; its
  test module covers enum defaults, versioned-envelope round-trip, and the
  ALLOW→SOFT→HARD / H3→H2→H1 state transitions.
- Engine-driven transitions are exercised end-to-end in
  `DirectPathPolicyLearnerTest` (soft/hard-disable, NO_DIRECT_SOLUTION, recovery
  to ALLOW) and in `core/data/.../TransportPolicyFamilyNormalizationTest`.

## Links

- [[Epic - Direct-mode transport policy and verdicts]]
- ripdpi-android-direct-mode-plan-2026-04-20
