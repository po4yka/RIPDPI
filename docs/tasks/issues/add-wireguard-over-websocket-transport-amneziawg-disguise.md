---
title: Add WireGuard-over-WebSocket transport with AmneziaWG disguise
type: task
status: backlog
area: rust-native
priority: high
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-16
updated: 2026-05-16
---

## Summary

Encapsulate WireGuard handshakes and data frames inside a WebSocket stream (WSS over 443). Combined with AmneziaWG's junk-packet prefix and randomized header values, this eliminates both the distinctive WG UDP fingerprint (handshake initiation MTU/checksum, message-type byte) and the well-known WG UDP port range.

## Context

WireGuard's UDP fingerprint is one of the easiest DPI signatures in the wild: a fixed 148-byte handshake initiation with type byte 0x01. Russian TSPU drops these inline. Wrapping the frames inside WSS makes them look like ordinary HTTP/1.1 Upgrade traffic to port 443. AmneziaWG's `Jc`/`Jmin`/`Jmax` junk parameters defeat the residual "first packet shape" heuristic by inserting random padding before the real handshake.

`docs/amneziawg-uri-scheme.md` already describes the configuration schema. This task adds the WS transport carrier.

## Acceptance criteria

- [ ] New crate `ripdpi-wireguard-ws` implementing the WireGuard-over-WSS transport adapter (encrypt/decrypt frames, drive WS framing).
- [ ] AmneziaWG junk-packet generation (Jc/Jmin/Jmax) is wired into the pre-handshake stream.
- [ ] Configuration via existing `core:data:model` typed schema (extend `WireguardOutbound`).
- [ ] Loopback test exercises a complete WG handshake through a WSS pair without any real internet.
- [ ] Telemetry: counter increments on successful WG handshake through the WS carrier.

## Risks / open questions

- WG userspace implementation: use `boringtun` or implement Noise_IK directly. `boringtun` is simpler but pulls in another crate dep with its own pinning concerns.
- Mobile MTU: tunneling WG inside WS inside TLS easily blows through 1500-byte MTU. PMTU discovery (see `add-quic-path-mtu-discovery-regression-test`) is relevant.

## Links

- `docs/amneziawg-uri-scheme.md`
- ws-tunnel-telegram
- AmneziaWG protocol spec
