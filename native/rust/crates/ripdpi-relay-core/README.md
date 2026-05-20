# ripdpi-relay-core

**Responsibility:** the shared relay backend — orchestration, the relay-session
abstraction, the connection pool, the capability surface, and the SOCKS bridge
that fronts the transports.
**Layer:** L7 — relay transports.

Relay-core ties the concrete transport crates together: it owns the
`RelaySession` abstraction (including `open_datagram` for datagram-capable
transports), runtime/config wiring, runtime validation, and the telemetry
surface. It rejects unsupported relay/mode combinations early rather than
dropping them silently.

## Stable identifiers / contracts

The `RelaySession` trait and the relay runtime-config shape are the contract
the Android relay bridge depends on. Relay selection is keyed by the
`relay_kind` string in `app_settings.proto` (see [`CONFIG_CONTRACTS.md`](../../../../docs/architecture/CONFIG_CONTRACTS.md) §5).

## Dependency direction

**Upstream:** `ripdpi-relay-mux` + the transport crates (`ripdpi-hysteria2`,
`ripdpi-masque`, `ripdpi-shadowtls`, `ripdpi-tuic`, `ripdpi-vless`,
`ripdpi-xhttp`). **Downstream:** `ripdpi-relay-android` → `libripdpi-relay.so`.

## Non-root fallback

Relay runs fully on non-rooted devices and calls no privileged operations — see
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md)
for the privileged path it does not use.

## Extension checklist

1. Implement the transport in its own crate behind the `RelaySession` contract.
2. Register it in relay-core's `backend` / `runtime` wiring.
3. Adding a new **relay kind** (a new `relay_kind` string) is a cross-cutting
   change — follow [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md) §2.

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md),
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md),
and [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md).
