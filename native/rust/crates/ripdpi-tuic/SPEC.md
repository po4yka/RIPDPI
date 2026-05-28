# SPEC — `ripdpi-tuic`

## Scope

TUIC v5 client over Quinn.

## Upstream

- EAimTY/tuic (https://github.com/EAimTY/tuic)
- Pin recorded in `SPEC_VERSION.md`

## Wire format (v5)

Constants from `protocol.rs`:

| Constant | Value | Meaning |
|---|---|---|
| `TUIC_VERSION` | `0x05` | Wire version |
| `COMMAND_AUTHENTICATE` | `0x00` | Auth frame |
| `COMMAND_CONNECT` | `0x01` | TCP connect |
| `COMMAND_PACKET` | `0x02` | UDP packet frame |

`TuicAddress` encodes destination as one of:

- `None` (UDP packet fragment without address)
- `Domain(host, port)` — host is 1..255 byte UTF-8
- `Socket(SocketAddr)` — IPv4 or IPv6

## QUIC setup

- Endpoint constructed in `endpoint.rs` with a custom socket spec.
- Migration handled in `migration.rs`.
- UDP packet flow in `udp.rs`; TCP CONNECT in `client.rs`.

## Known divergences from upstream

- v4 is intentionally unsupported; `docs/architecture/tuic-v4-policy.md` records the v5-only decision and the version-unsupported classifier surface.
- UDP forward mode is native/datagram-only; `docs/architecture/tuic-udp-forward-mode-audit.md` records the decision to defer a reliable-stream `quic` mode until telemetry justifies it.
- App-level keepalive policy not yet defined; see `docs/tasks/issues/add-tuic-heartbeat-and-keepalive-policy.md`.

## Non-goals

- Server-side TUIC.
- v4 wire support.
