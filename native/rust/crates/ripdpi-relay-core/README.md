# ripdpi-relay-core

**Responsibility:** the shared relay backend — orchestration, the relay-session
abstraction, the connection pool, the capability surface, and the SOCKS bridge
that fronts the transports.
**Layer:** L7 — relay transports.

Relay-core ties the concrete transport crates together: it owns the
`RelaySession` abstraction (including `open_datagram` for datagram-capable
transports), runtime/config wiring, runtime validation, and the telemetry
surface. It rejects unsupported relay/mode combinations early rather than
dropping them silently. NaiveProxy is registered with `builder = None` and
`fallback_mode = "subprocess"` because Kotlin supervises the helper process
outside relay-core. WebTunnel is an in-repository Rust pluggable-transport
helper binary, while Snowflake and obfs4 are external pluggable-transport
binaries; none of those PT helpers are relay-core descriptor rows.

## Stable identifiers / contracts

The `RelaySession` trait and the relay runtime-config shape are the contract
the Android relay bridge depends on. Relay selection is keyed by the
`relay_kind` string in `app_settings.proto` (see [`CONFIG_CONTRACTS.md`](../../../../docs/architecture/CONFIG_CONTRACTS.md) §5).

## Dependency direction

**Upstream:** `ripdpi-relay-mux`, `ripdpi-relay-tls-transports`, and transport
crates including `ripdpi-hysteria2`, `ripdpi-masque`, `ripdpi-shadowtls`,
`ripdpi-tuic`, `ripdpi-vless`, `ripdpi-xhttp`, `ripdpi-trojan`,
`ripdpi-anytls`, `ripdpi-shadowsocks`, and `ripdpi-tor`. **Downstream:**
`ripdpi-relay-android` → `libripdpi-relay.so`.

## Non-root fallback

Relay runs fully on non-rooted devices and calls no privileged operations — see
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md)
for the privileged path it does not use.

## Extension checklist

1. Implement the transport in its own crate behind the `RelaySession` contract.
2. Register it in relay-core's `backend` / `runtime` wiring.
3. Adding a new **relay kind** (a new `relay_kind` string) is a cross-cutting
   change — follow [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md) §2.

## Tor backend

The Tor relay backend landed as `RelayBackendConfig::Tor`, `RelayKind::Tor`,
`RelayBackend::Tor`, and the `ripdpi-tor` crate. It wraps Arti (`arti-client`)
and adapts `TorClient::connect((host, port)) -> DataStream` to relay-core's
`connect_tcp(target) -> BoxedIo`.

Non-goals for this backend are UDP over Tor (`udp_capable=false`), running a Tor relay or onion service, replacing the fast proxy relays as the default, custom Tor path policy, and bundling PT implementations inside Arti. In censored profiles, Tor bootstrap must start through configured bridges plus external PT binaries; direct directory bootstrap is not an acceptable fallback for the bridge+PT profile path.

## Transport-descriptor seam

`RelayTransportDescriptor` (`src/transport_descriptor.rs`, re-exported from the
crate root with `RELAY_TRANSPORT_DESCRIPTORS` and `relay_transport_descriptor`)
is the `relay_kind`-keyed **source of truth** for a relay transport's generic
capability profile: one row per `relay_kind`, carrying the static facts — kind
string, label, SOCKS capability profile (TCP / UDP / connection reuse), and
outbound-bind-IP support.

`runtime_validation` resolves the generic capability decisions through this
table: `planned_backend_capabilities` reads TCP / UDP / reuse from it, and the
outbound-bind-IP validation gate reads `supports_outbound_bind_ip`. Relay
selection, config parsing, and runtime dispatch still flow through the `match
RelayKind` statements in `runtime_validation.rs` and the `BUILDERS` slice. The
`relay_transport_descriptors_cover_every_kind_exactly_once` and
`relay_planned_capabilities_are_pinned_for_every_kind` crate tests pin the
table against every `RelayKind`. Finalmask support, pool tuning, chain-relay
upstream description, and the NaiveProxy subprocess fallback are intentionally
**excluded** from the descriptor — they vary with a transport sub-mode (VLESS
Reality's `xhttp`) or are backend-specific, not keyed by the `relay_kind`
string alone. Migrating those remaining matches onto the descriptor is the
tracked future refactor in
[`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md)
§2, "The transport-descriptor seam".

---
See [`NATIVE_RUST.md`](../../../../docs/architecture/NATIVE_RUST.md),
[`ROOT_HELPER_CONTRACT.md`](../../../../docs/architecture/ROOT_HELPER_CONTRACT.md),
and [`FEATURE_EXTENSION_GUIDE.md`](../../../../docs/architecture/FEATURE_EXTENSION_GUIDE.md).
