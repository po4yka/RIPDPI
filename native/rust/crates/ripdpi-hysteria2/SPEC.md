# SPEC — `ripdpi-hysteria2`

## Scope

Hysteria 2 client over Quinn, including Salamander obfuscation, remote port hopping, and H3 CONNECT / CONNECT-UDP transports.

## Upstream

- apernet/hysteria (https://github.com/apernet/hysteria)
- Pin recorded in `SPEC_VERSION.md`

## Transport stack

| Layer | Module | Notes |
|---|---|---|
| QUIC | `quic_transport/`, Quinn | RFC 9000 path validation enabled |
| Salamander obfuscation | `salamander.rs` | XOR keyed by server secret |
| Port hopping | `hopping_policy.rs`, `hopping_socket.rs` | Validated port union; fixed or randomized schedule; remote port and local socket rotation on one QUIC session |
| H3 transports | `quic_transport/h3.rs` | `connect`, `connect-udp` (RFC 9298) |
| Auth | `tls_quic.rs` | HTTP/3 `POST https://hysteria/auth`, opaque `Hysteria-Auth` value, success status 233 |

## Migration

Post-handshake socket rebind is implemented in `migration.rs`; Quinn performs RFC 9000 path validation on rebind.

## Remote port hopping

`PortHoppingConfig` accepts ports 1 through 65535 as a comma union with hyphen ranges.
It merges overlapping ranges before expansion. Intervals use whole milliseconds,
with a 5000 ms minimum, 30000 ms default, and 9223372036854 ms maximum.
The native JSON fields are `hysteriaPortHopPorts`, `hysteriaPortHopIntervalMillis`,
and `hysteriaPortHopIntervalMaxMillis`; the same fields apply to resolved chain hops.

The adapter rotates the remote port and protected local UDP socket, while Quinn
sees one logical peer. It keeps the previous receive socket until the next hop.
Source migration rebinds the same adapter and keeps its remote policy and deadline.
A protection failure stops the adapter before the replacement socket sends data.
No background hopping task exists; the QUIC receive driver polls the timer.

The independent peer cases in `test-lab/acceptance/peers/catalog.py` cover IPv4 and
IPv6, Salamander on and off, and fixed and randomized windows. They pass serialized
native JSON through `RelayRuntime`, use persistent SOCKS TCP and UDP sessions, and
record physical port changes and destination payload receipts. They use a pinned
Hysteria release on loopback and do not establish Android or filtered-network acceptance.

## Known divergences from upstream

- `contract-fixtures/hysteria2/v2/salamander/` contains a fixture-walker harness and one synthetic `hello-zero-salt.bin` regression vector. Broader upstream-captured Salamander vectors are still tracked in `docs/tasks/issues/add-hysteria2-salamander-obfuscation-conformance-fixtures.md`.
- Hysteria v1 is intentionally unsupported.

## Non-goals

- Server-side Hysteria.
- v1 client; Hysteria v1 was removed entirely per `docs/adr/0004-protocol-support-policy.md` (legacy, never carried traffic).
