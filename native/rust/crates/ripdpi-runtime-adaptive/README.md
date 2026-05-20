# ripdpi-runtime-adaptive

**Layer:** L3 — domain logic.

## Responsibility

Adaptive runtime tuning: it adjusts strategy parameters at runtime in response
to observed connection outcomes — adaptive fake-TTL, split placement, TLS
record sizing, UDP burst behavior, QUIC fake-profile selection, retry-stealth
pacing, and connection morphing.

## What belongs here

- `adaptive_fake_ttl` — fake-TTL adaptation derived from server SYN-ACK TTL.
- `adaptive_tuning` — split/TLS-record/UDP-burst/QUIC adaptive dimensions.
- `retry_stealth` — family cooldowns, backoff, jitter, candidate diversification.
- `morph_policy`, `strategy_context`, and the `adaptive_port` trait.

## What must not be added here

- OS primitives, sockets, raw-packet emission (L5).
- Runtime composition / wiring (L4).
- Dependencies on L4/L5 crates, or on `jni` / `android-support`.

## Dependencies

- **Upstream:** `ripdpi-config`, `ripdpi-desync`, `ripdpi-failure-classifier`,
  `ripdpi-packets`, `ripdpi-proxy-config`, `ripdpi-runtime-decision-ports`,
  `ripdpi-runtime-policy`; `ring`, `serde`, `serde_json`, `metrics`, `tracing`.
- **Downstream:** `ripdpi-runtime-services` (and `ripdpi-bench`).

## Public API stability

The `adaptive_port` trait is the contract implemented by
`ripdpi-runtime-services` — keep it stable. Tuning heuristics inside the
modules may evolve freely as long as the port contract holds.

---
Part of the RIPDPI native Rust workspace — see
[`docs/architecture/NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md)
for the full crate taxonomy and dependency-direction policy.
