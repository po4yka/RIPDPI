# ripdpi-runtime-strategy

**Layer:** L3 — domain logic.

## Responsibility

Strategy selection, scoring, and evolution for the runtime. Ranks desync
strategy arms, applies penalties and cooldowns, drives outbound failover, and
runs the Geneva-style strategy evolver that explores adaptive dimensions.

## What belongs here

- `scoring` and `arm_penalties` — strategy-arm ranking and penalty/cooldown logic.
- `outbound_failover` — failover ordering across strategy arms.
- `profiles` — adaptive strategy profiles.
- `strategy_evolver` — epsilon-greedy / UCB1 combo exploration.

## What must not be added here

- OS primitives, sockets, or raw-packet emission (L5).
- Runtime composition / wiring (L4) and config translation (L2).
- Dependencies on L4/L5 crates, or on `jni` / `android-support`.

## Dependencies

- **Upstream:** `ripdpi-config`, `ripdpi-desync`, `ripdpi-failure-classifier`,
  `ripdpi-shared-priors` (offline-learner priors); `serde`, `serde_json`, `tracing`.
- **Downstream:** `ripdpi-runtime-services`, plus the Android platform adapter
  and `ripdpi-bench`.

## Public API stability

The evolver and scoring types are consumed by `ripdpi-runtime-services`; keep
the selection/ranking surface stable. `ripdpi-shared-priors` integration is
fail-secure — preserve that contract when changing prior consumption.

---
Part of the RIPDPI native Rust workspace — see
[`docs/architecture/NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md)
for the full crate taxonomy and dependency-direction policy.
