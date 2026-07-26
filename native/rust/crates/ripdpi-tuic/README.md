# ripdpi-tuic

**Responsibility:** the TUIC v5 relay transport — QUIC-based TCP and UDP
forwarding with connection migration.
**Layer:** L7 — relay transports.

## Stable identifiers / contracts

Selected by `relay_kind = "tuic_v5"`. The TUIC v5 protocol (authentication,
TCP/UDP relay framing, migration) is a fixed protocol contract that must
interoperate with external TUIC servers. Congestion control and 0-RTT are
exposed as typed settings (`relay_tuic_congestion_control`, `relay_tuic_zero_rtt`).

## Dependency direction

**Upstream:** `ripdpi-native-protect` plus `quinn`, `tokio`, and `rustls`. **Downstream:**
`ripdpi-relay-core`.

## Non-root fallback

No privileged operations — runs fully on non-rooted devices. See
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md).

## Extension checklist

1. Add TUIC features behind the existing protocol types.
2. Never change the v5 wire protocol — it is an interop contract.

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md),
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md),
and [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md).
