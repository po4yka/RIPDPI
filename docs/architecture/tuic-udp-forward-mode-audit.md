# TUIC UDP Forward Mode Audit

> Status: **audit complete; toggle decision recorded; implementation pending**. Authored: 2026-05-15. Tracking task: `docs/tasks/issues/add-tuic-udp-forward-mode-toggle-native-vs-quic.md`.

## Question

TUIC v5 upstream supports two UDP forwarding modes:

- **`native`** — UDP packets are carried as QUIC datagrams. Lower overhead, no retransmission, requires server to advertise `max_datagram_size` > 0.
- **`quic`** — UDP packets are carried over a reliable bidirectional QUIC stream. Higher overhead, retransmits, works when datagrams are blocked or the server disables them.

Which mode does `ripdpi-tuic` implement today, and should the other mode be exposed?

## Current state

`ripdpi-tuic` is **native-mode only**:

- `protocol.rs:14` defines `COMMAND_PACKET = 0x02` and the encoder at `protocol.rs:151` puts the packet bytes directly into a QUIC datagram via `Self::encode`.
- `udp.rs::dispatch_incoming_datagrams` listens on `connection.read_datagram()` only; there is no stream-based UDP receiver.
- `udp.rs::UdpSession::send_packet` calls `connection.send_datagram(...)` and surfaces an error if `max_datagram_size` is `None`.
- `client.rs:39-58` gates UDP support on `max_datagram_size.is_some()` and skips `dispatch_incoming_datagrams` otherwise.

A server that disables QUIC datagrams (or a path that drops them) will return an "TUIC datagram relay is not available on this connection" error to the caller.

## Decision

**Keep native-only for now, document the fallback diagnostic, and defer `quic`-mode implementation** until field telemetry shows a non-trivial population of TUIC deployments where datagrams are not available.

Rationale:

- Native mode is the dominant deployment.
- The current "datagrams not available" error is correctly surfaced; callers can fall back to a different profile.
- Adding `quic`-mode is meaningful work: a separate stream-multiplexed packet path, a new framing layer, and matching server-side support in `EAimTY/tuic`.
- Premature toggle complicates the config surface without evidence.

## What changes anyway

A short addition to `Config` would let the caller *opt into* a diagnostic-only mode that, on connect, asserts the server's `max_datagram_size` and emits a structured error if datagrams are unavailable, rather than waiting for the first `send_packet` to fail. That is a smaller follow-up than full `quic`-mode support and can be filed separately if telemetry justifies it.

## Implementation outline (when `quic`-mode is needed)

1. Add `Config.udp_forward_mode: UdpForwardMode { Native, Quic }` with `#[serde(default)]` (default `Native`).
2. Behind `Quic`, allocate a long-lived bidirectional stream after `AUTHENTICATE` and use length-prefixed framing for `PACKET` frames instead of QUIC datagrams.
3. Server-side compatibility check at connect time; refuse to start `Quic` mode if the server does not advertise stream-mode support.
4. Telemetry counter per mode chosen, mirroring the existing migration snapshot.

## Owner

Native-transport owner picks this up if datagram-availability metrics warrant it.
