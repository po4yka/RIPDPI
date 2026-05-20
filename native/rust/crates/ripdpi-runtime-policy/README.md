# ripdpi-runtime-policy

**Layer:** L3 — domain logic.

## Responsibility

The runtime policy engine: it decides *which* transport and strategy a
connection should use based on learned outcomes. Holds per-app-family memory,
direct-path block-class learning, transport policy, and the policy ports that
the runtime-services layer implements against.

## What belongs here

- `AppFamilyMemory` — per-app-family outcome memory.
- `direct_path_learning` — direct-path block-class classification and learning.
- `runtime_policy` and `transport_policy` decision logic.
- The `PolicyPort` / `DirectPathLearningPort` traits (hexagonal ports).

## What must not be added here

- OS primitives, sockets, raw packets, `VpnService.protect` — that is L5
  (`ripdpi-runtime-platform`).
- Runtime composition / wiring — that is L4 (`ripdpi-runtime-services`).
- Dependencies on L4/L5 crates, or on `jni` / `android-support`.

## Dependencies

- **Upstream:** `ripdpi-config`, `ripdpi-desync`, `ripdpi-failure-classifier`,
  `ripdpi-packets`, `ripdpi-runtime-decision-ports`, `ripdpi-session`; `ring`,
  `serde`, `serde_json`, `tracing`.
- **Downstream:** `ripdpi-runtime-adaptive`, `ripdpi-runtime-services`.

## Public API stability

The `PolicyPort` / `DirectPathLearningPort` traits are the contract edge
implemented by `ripdpi-runtime-services` — keep them stable. Internal scoring
and memory representations may evolve as long as the port contracts hold.

---
Part of the RIPDPI native Rust workspace — see
[`docs/architecture/NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md)
for the full crate taxonomy and dependency-direction policy.
