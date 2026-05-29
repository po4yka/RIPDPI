---
title: Add N-hop native chain composition
type: task
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: epic-multi-hop-proxy-chains
blocks: []
blocked_by: []
created: 2026-05-29
updated: 2026-05-29
---

- [ ] #task Add N-hop native chain composition #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Summary

Extend the relay-core `chain_relay` path to compose an ordered list of N hops (currently fixed at two), protecting every hop's outbound socket and resolving trust/caveat per hop.

## Context

The native chain composition today folds exactly entry → exit. With the model generalized by [[Generalize chain relay to N hops model and migration]], the relay-core must fold the composition across the ordered hop list, and `ChainRelayTrustDomainResolver` must compute per-hop and cumulative trust + latency caveat for N hops.

## Acceptance criteria

- [ ] Relay-core composes 2-, 3-, and 4-hop chains; native tests cover each hop count.
- [ ] Every non-loopback outbound socket created for each hop is preceded by a successful `protect_socket(fd)` call — verified by the protect audit grep in `.claude/rules/vpnservice-protect-invariant.md`.
- [ ] Shutdown joins bounded per-hop handler work cleanly (same invariant as existing relay kinds); no orphaned tasks on stop.
- [ ] `ChainRelayTrustDomainResolver` resolves trust domain and cumulative latency caveat across N hops; resolver test extended.
- [ ] Every new async fn carries a `// cancel-safe:` / `// NOT cancel-safe:` annotation; any `unsafe` carries a `// SAFETY:` block per `.claude/rules/llm-rust-prompts.md`.
- [ ] `cargo nextest run --workspace --locked` is green; chain crate(s) pass clippy at the workspace lint floor.

## Source references

**Reference (xivpn):** layered multi-proxy tunnels — concept only.

**Adapt:** the existing two-hop relay-core composition and supervisor join logic.

**Invent:** the fold over an ordered hop list, per-hop protect wiring, and N-hop trust/caveat resolution.

## Links

- [[Epic - Multi-hop proxy chains]]
