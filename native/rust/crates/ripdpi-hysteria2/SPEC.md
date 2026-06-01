# SPEC — `ripdpi-hysteria2`

## Scope

Hysteria 2 client over Quinn, including Salamander obfuscation, port-hopping schedule, and H3 CONNECT / CONNECT-UDP transports.

## Upstream

- apernet/hysteria (https://github.com/apernet/hysteria)
- Pin recorded in `SPEC_VERSION.md`

## Transport stack

| Layer | Module | Notes |
|---|---|---|
| QUIC | `quic_transport/`, Quinn | RFC 9000 path validation enabled |
| Salamander obfuscation | `salamander.rs` | XOR keyed by server secret |
| Port hopping | `port_hopping.rs` | Window-scheduled UDP rebind |
| H3 transports | `quic_transport/h3.rs` | `connect`, `connect-udp` (RFC 9298) |
| Auth | `auth.rs` | Bearer / preshared / privacy_pass |

## Migration

Post-handshake socket rebind is implemented in `migration.rs`; Quinn performs RFC 9000 path validation on rebind.

## Known divergences from upstream

- `contract-fixtures/hysteria2/v2/salamander/` contains a fixture-walker harness and one synthetic `hello-zero-salt.bin` regression vector. Broader upstream-captured Salamander vectors are still tracked in `docs/tasks/issues/add-hysteria2-salamander-obfuscation-conformance-fixtures.md`.
- Hysteria v1 is intentionally unsupported.

## Non-goals

- Server-side Hysteria.
- v1 client; Hysteria v1 was removed entirely per `docs/adr/0004-protocol-support-policy.md` (legacy, never carried traffic).
