# ripdpi-runtime-services

**Layer:** L4 — runtime / application.

## Responsibility

The runtime decision-services composition layer. It wires the L3 decision
crates (`ripdpi-runtime-policy`, `-adaptive`, `-strategy`) into a single
`ServicesState`, supplies the concrete implementations of the policy / adaptive
/ background-probe ports, and exposes decision helpers to the proxy runtime.

## What belongs here

- `ServicesState` — the aggregated runtime services state.
- `policy_port_impl` / `adaptive_port_impl` / `background_probes_impl` — the
  concrete port implementations over the L3 crates.
- `decision_helpers` and `strategy_evolution` orchestration glue.

## What must not be added here

- New *domain* logic — policy belongs in `ripdpi-runtime-policy`, scoring in
  `ripdpi-runtime-strategy`, tuning in `ripdpi-runtime-adaptive`. This crate
  composes; it must stay thin and resist becoming a god-module.
- OS primitives / sockets (L5) and config translation (L2).
- `jni` / `android-support` — this crate is JNI-free.

## Dependencies

- **Upstream:** `ripdpi-runtime-api`, `ripdpi-runtime-policy`,
  `ripdpi-runtime-adaptive`, `ripdpi-runtime-strategy`, `ripdpi-config`,
  `ripdpi-desync`, `ripdpi-failure-classifier`, `ripdpi-proxy-config`;
  `arc-swap`, `tracing`.
- **Downstream:** `ripdpi-proxy-runtime-adapter`,
  `ripdpi-proxy-runtime-desync-adapter`.

## Public API stability

The public surface (`ServicesState`, `decision_helpers`) is consumed only by
the proxy-runtime adapters — moderately stable, internal to the runtime stack.
Keep it small; growth here is a signal that logic leaked up from L3.

---
Part of the RIPDPI native Rust workspace — see
[`docs/architecture/NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md)
for the full crate taxonomy and dependency-direction policy.
