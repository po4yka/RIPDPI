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

- v4 is unsupported pending policy decision; see
  `docs/tasks/issues/add-tuic-v4-fallback-or-version-detection.md`.
- UDP forward mode (native vs quic) is not exposed as a toggle yet;
  see
  `docs/tasks/issues/add-tuic-udp-forward-mode-toggle-native-vs-quic.md`.
- App-level keepalive policy not yet defined; see
  `docs/tasks/issues/add-tuic-heartbeat-and-keepalive-policy.md`.

## Non-goals

- Server-side TUIC.
- v4 wire support.
