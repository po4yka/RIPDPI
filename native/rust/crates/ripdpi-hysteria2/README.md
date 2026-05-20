# ripdpi-hysteria2

**Responsibility:** the Hysteria2 relay transport — QUIC-based, with Salamander
obfuscation, port hopping, UDP relay, and connection migration.
**Layer:** L7 — relay transports.

## Stable identifiers / contracts

Selected by `relay_kind = "hysteria2"`. The Hysteria2 wire protocol (varint
framing, Salamander obfuscation, port-hopping scheme) is a fixed protocol
contract that must interoperate with external Hysteria2 servers.

## Dependency direction

**Upstream:** none internal (`quinn`, `tokio`, `rustls`). **Downstream:**
`ripdpi-relay-core`, `ripdpi-masque`.

## Non-root fallback

No privileged operations — runs fully on non-rooted devices. See
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md).

## Extension checklist

1. Add Hysteria2 features (migration / port-hopping tuning) behind the existing
   protocol types.
2. Never change the wire protocol — it is an interop contract.

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md),
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md),
and [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md).
