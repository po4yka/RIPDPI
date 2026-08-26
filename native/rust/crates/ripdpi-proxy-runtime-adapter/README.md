# ripdpi-proxy-runtime-adapter

**Layer:** L4 — runtime / application.

## Responsibility

The composition / wiring adapter for the local SOCKS5 proxy runtime. It binds
the config, decision-services, desync, platform, and ws-bootstrap layers into
the inputs `ripdpi-proxy-runtime` needs — failure mapping, IP-fragmentation
wiring, protocol-payload handling, raw-packet requirement checks, response
triggers, TCP rotation, UDP desync, and ws-bootstrap glue.

## What belongs here

- Adapter / wiring modules that connect lower layers to the proxy runtime
  (`desync_platform`, `failure`, `ip_fragmentation`, `platform`,
  `protocol_payload`, `raw_packet_requirements`, `response_triggers`,
  `tcp_rotation`, `udp_desync`, `ws_bootstrap`).
- `#![forbid(unsafe_code)]` — this crate carries no `unsafe`; keep it that way.

## What must not be added here

- New domain logic (policy/strategy/adaptive — those are L3) or new OS
  primitives (L5). This is a wiring crate; with the most internal dependencies
  in the runtime stack it is the prime god-adapter risk — keep it composition-only.
- `jni` / `android-support` — this crate is JNI-free.

## Dependencies

- **Upstream:** `ripdpi-proxy-config`, `ripdpi-config`, `ripdpi-failure-classifier`,
  `ripdpi-proxy-runtime-desync-adapter`, `ripdpi-runtime-api`,
  `ripdpi-runtime-decision-engine`, `ripdpi-runtime-decision-ports`,
  `ripdpi-runtime-platform`, `ripdpi-runtime-services`, `ripdpi-session`,
  `ripdpi-socks5-core`, `ripdpi-ws-bootstrap`; plus
  `base64`, `libc`, `metrics`, `nix`, `socket2`, `tracing`.
- **Downstream:** `ripdpi-proxy-runtime` (the proxy runtime crate; the runtime
  core of `libripdpi.so`).

## Public API stability

Consumed only by `ripdpi-proxy-runtime` — internal wiring, not a broad
contract. The public modules can evolve with the runtime, but new behavior
should land in the layer that owns it, not be absorbed here.

---
Part of the RIPDPI native Rust workspace — see
[`docs/architecture/NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md)
for the full crate taxonomy and dependency-direction policy.
