---
title: Add constant-rate traffic shaping with VoIP camouflage profile
type: task
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: null
blocks: []
blocked_by: []
created: 2026-05-16
updated: 2026-05-16
---

- [ ] #task Add constant-rate traffic shaping with VoIP camouflage profile #repo/RIPDPI #area/rust-native #status/backlog 🔼

## Summary

Add an outbound traffic-shaping layer that emits packets at a fixed rate and size (e.g. 200-byte UDP every 20 ms — Opus-over-RTP shape) regardless of payload arrival rate. This defeats both inter-packet-arrival-time (IPAT) and packet-size-distribution fingerprinting that DPI uses to distinguish "bulk file transfer masquerading as VoIP" from real VoIP.

## Context

Even when the wire protocol is indistinguishable (e.g. MASQUE+H3), the *traffic shape* leaks the underlying application. Bulk transfers burst then idle; VoIP holds a constant rate. ML-based DPI classifiers in Russia, Iran, and the GFW exploit this for high- precision blocking.

A shaper pads outgoing payloads to a fixed size and emits them on a fixed clock; incoming reverse direction is also padded so the peer sees the same shape. Cost: ~50% bandwidth overhead at low real-payload rates, mostly invisible at higher rates because the shaper's natural rate accommodates the real payload.

## Acceptance criteria

- [ ] New crate `ripdpi-traffic-shape` with a `Shaper` trait that wraps any `AsyncRead + AsyncWrite` stream.
- [ ] At least two preset profiles: `opus_voip` (200-byte / 20 ms) and `webrtc_video` (variable but bounded).
- [ ] Configurable via `core:data:model` typed schema.
- [ ] Unit tests verify: outgoing rate stays within ±5% of target over 1000 ticks; size distribution is constant; reverse-path padding round-trips cleanly.
- [ ] Telemetry counters for bytes-padded vs bytes-real (so operators can see the overhead).

## Risks / open questions

- Shaper is opt-in per profile; default off. Most users won't pay the overhead.
- Battery cost on mobile: a 20-ms timer keeps the radio awake. Document and gate behind a low-power-aware policy.
- Composability with other transports: shaper sits between the app and the transport stack; verify it composes with VLESS, MASQUE, Hysteria 2.

## Links

- [[Epic - Control-plane hardening]]
- WebRTC dummy-packet padding (RFC 6562)
