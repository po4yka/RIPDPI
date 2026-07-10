---
title: Fix Kotlin chain resolution contracts
type: task
status: doing
area: relay
priority: high
owner: Codex relay-chain coordinator
parent: null
blocks: []
blocked_by: []
created: 2026-07-10
updated: 2026-07-10
---

## Goal

Make Kotlin chain resolution reject QUIC-capable middle hops, fully resolve ShadowTLS inner profiles, and preserve explicitly configured per-hop TLS fingerprints without inventing defaults.

## Audit lanes

- QUIC middle-hop capability and typed rejection: read-only specialist lane.
- ShadowTLS inner-profile resolution and identity preservation: read-only specialist lane.
- Per-hop TLS fingerprint propagation and defaulting: read-only specialist lane.
- Shared resolver/test files and all implementation changes: serialized coordinator lane.

## Ship definition

- Middle hops that require UDP or QUIC fail before native startup with a typed actionable error.
- ShadowTLS hops resolve their referenced inner profile completely and preserve its identity.
- Every hop carries the selected TLS fingerprint exactly; no resolver-level default replaces an absent or explicit value.
- Regression tests cover each confirmed bug and relevant focused/broader gates pass.
