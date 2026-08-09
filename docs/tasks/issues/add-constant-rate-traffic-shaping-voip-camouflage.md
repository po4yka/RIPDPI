---
id: RST-1786264762917193
title: Add constant-rate traffic shaping with VoIP camouflage profile
kind: feature
status: backlog
area: rust-native
priority: medium
owner: unassigned
parent: EPC-1786264762917282
blocked_by: []
spec_mode: required
openspec_change: rst-1786264762917193-add-constant-rate-traffic-shaping-voip-camouflage
created: 2026-05-16
updated: 2026-06-11
---

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

- [[epic-transport-obfuscation-research]]
- WebRTC dummy-packet padding (RFC 6562)

## Design spike — constant-rate VoIP-camouflage shaping (2026-06-11)

> The acceptance criteria above describe an imagined `ripdpi-traffic-shape` crate; they are NOT checked off. This pass is a **design spike** per `epic-transport-obfuscation-research` — no production code is merged. The note below is the deliverable; implementation re-files as a graduation task (see Go / No-Go).

### Scope and grounding

Read-only review of this task against the worktree. Confirmed: `ripdpi-traffic-shape` does **not** exist; there is **no** constant-rate/fixed-clock pacing layer anywhere in the native tree.

### (a) Where the shaper would sit relative to protect + the transport stack

The `VpnService.protect()` invariant lives at the socket-creation boundary (`ripdpi-native-protect`, `ripdpi-runtime-platform/src/vpn_protect.rs`, and per-transport `protect.rs` helpers in `ripdpi-tunnel-core`, `ripdpi-ws-tunnel`). A traffic shaper is a **payload timing/sizing layer that sits strictly above an already-established, already-protected connection** — it never opens a socket, so it does not touch the protect invariant. That is the one clean part of the design.

The hard part is *what* it wraps. The transport crates expose **two distinct surfaces**, and the task conflates them:

1. **Stream surface** — `AsyncRead + AsyncWrite` (e.g. `ripdpi-hysteria2/src/quic_transport/stream.rs`, `ripdpi-masque/src/h3/tcp_bridge.rs`, `ripdpi-tuic/src/tcp.rs`). This is a byte stream with **no packet boundaries**, so "emit a 200-byte packet every 20 ms" is not a natural operation here. A shaper at this layer can pad bytes but cannot control how the QUIC/TLS layer below frames them into wire datagrams — congestion control, coalescing, and QUIC packetization sit *below* the `AsyncWrite`.
2. **Datagram surface** — `ripdpi-hysteria2/src/quic_transport/datagram.rs` (`QuicDatagramTransport`, peer-negotiated `max_datagram_size()`), and the UDP-relay APIs in hysteria2/masque/tuic `udp.rs`. This is where a 1:1 payload→wire-datagram mapping actually exists, and where "fixed size on a fixed clock" is meaningful.

A genuine Opus-over-RTP shape is a **datagram** phenomenon. The task's acceptance criterion — a `Shaper` trait wrapping `AsyncRead + AsyncWrite` — targets the wrong surface; padding a byte stream does not reliably produce the on-wire packet-size distribution the threat model cares about, because the QUIC layer re-packetizes underneath it. Composability with VLESS (TCP-stream-oriented) is especially weak. **Net: the only semantically honest insertion point is the QUIC-datagram surface shared by Hysteria2 and MASQUE (`quic_transport/datagram.rs`), not a stream wrapper.**

### (b) Prototype profile spec — `opus_voip` (documented spec, NOT merged code)

```
profile opus_voip:
  direction:            forward-only (egress) in the first slice
  wire_unit:            QUIC unreliable datagram (datagram.rs send path)
  packet_size_bytes:    200            # post-pad fixed size incl. session/relay header
  emit_interval_ms:     20             # fixed clock => 50 pkt/s == Opus 20ms framing
  jitter_ms:            +/-2 (uniform) # avoids a too-perfect clock that is itself a tell
  pad_byte_policy:      random fill (matches existing hysteria2 padding: rand fill_bytes)
  underflow (no payload):  emit a full-size dummy datagram (cover traffic)
  overflow  (>200B-hdr):   fragment across subsequent ticks; never burst
  feasibility_gate:     require max_datagram_size() >= 200; if peer did not negotiate
                        datagrams (None), profile is unavailable — never silently fall back
  reverse_padding:      OUT OF SCOPE for prototype (no server cooperation exists)
  telemetry:            counter(bytes_real), counter(bytes_padded), counter(dummy_datagrams)
```

Values are grounded in existing code: hysteria2 already random-fills padding, and `max_datagram_size()` is already the negotiated ceiling the UDP relay fragments against — the feasibility gate reuses an existing check rather than inventing one.

### (c) Mobile cost analysis — the 20 ms timer

The most decisive finding. A 20 ms fixed clock means **50 wakeups/second that never go idle for the life of the flow** (including the underflow/dummy-datagram case). On Android this is expensive three ways:

1. **Radio stays in high-power (RRC connected) state** — a constant 50 pkt/s egress prevents the modem dropping to idle/DRX. This is exactly the battery profile `android-vpn-lifecycle.md` warns about; the single largest cost.
2. **No native low-power hook exists to gate it.** A grep across `native/rust/crates` for `low.?power|doze|battery|power_save|standby_bucket|interactive` hits only test files and an unrelated `ripdpi-mieru/src/loopback.rs`. `ripdpi-runtime-policy` models transport *selection*, not power state. So the task's "gate behind a low-power-aware policy" has **nothing to attach to** — the gate must be built first (Kotlin Doze/App-Standby/interactive signal → JNI → a new policy input).
3. **Doze corrupts the shape.** Timers misfire under Doze; a shaper whose value is a *perfectly regular* clock is exactly what Doze makes *irregular* — a VoIP shape that periodically stutters is itself a tell, unless the VPN holds a foreground-service wakelock the whole time (deepening cost 1).

### (d) Verdict reasoning — unique advantage vs cost/detectability

**Unique advantage is narrow** — shaping defends only against ML classifiers keying on IPAT + size distribution. RIPDPI already ships wire-indistinguishable cover protocols (MASQUE+H3, Reality, ShadowTLS) and per-message padding (hysteria2 length-prefixed padding, `Hysteria-Padding` H3 header); the shaper adds only the *temporal* dimension, and only against that classifier family. **Detectability cuts both ways** — a too-perfect constant rate is itself a fingerprint (real Opus has DTX silence-suppression, comfort-noise, jitter); matching real VoIP convincingly is a large modeling effort beyond "200B/20ms". **Cost is real and the gate is missing**, and **bidirectional shaping is unbuildable** from RIPDPI's client-only crates (no server-side padding cooperation in any deploy template) — half the threat model (download-direction shape) is unaddressable without out-of-repo server work.

## Go / No-Go (2026-06-11)

**Verdict: CONDITIONAL-GO (leaning skeptical).** The QUIC-datagram surface gives RIPDPI a real, honest insertion point and the temporal dimension is a genuine gap in the current padding — but three repo-grounded facts cap the payoff: the task's stream-wrapper framing targets the wrong surface; the assumed low-power gate does not exist (and Doze would corrupt the shape without it); and the "reverse path padded too" half needs server cooperation the client-only crates cannot provide. Worth a forward-only datagram prototype **only after the power gate is built**; the bidirectional/stream-wrapper scope is dropped.

**Graduation target.** Re-files under `epic-transport-obfuscation-research` (NOT a standalone `ripdpi-traffic-shape` crate). Minimal first slice: a forward-only egress datagram pacer on the existing shared `QuicDatagramTransport` (`native/rust/crates/ripdpi-hysteria2/src/quic_transport/datagram.rs`) — one `opus_voip` profile (200B/20ms/±2ms jitter, random pad fill), feasibility-gated on `max_datagram_size() >= 200`, default-off, with the three telemetry counters. **HARD PREREQUISITE:** build the missing low-power policy input first. Explicitly out of the first slice: the `AsyncRead+AsyncWrite` Shaper trait, VLESS/stream-surface support, the `webrtc_video` profile, and reverse-path padding.

## Work log

- 2026-06-05: No implementation found — `ripdpi-traffic-shape` crate does not exist under native/rust/crates/, no Shaper trait, no opus_voip/webrtc_video profiles, no schema config, no tests. All acceptance criteria remain open.
- 2026-06-11 (design spike, conditional-go): Delivered the design note above (insertion-point analysis, `opus_voip` profile spec, mobile-cost finding, verdict). Key findings: the shaper belongs on the QUIC-datagram surface (`ripdpi-hysteria2/src/quic_transport/datagram.rs`), not the `AsyncRead+AsyncWrite` stream surface the criteria assumed; there is no native low-power hook to gate the 20ms timer (must be built first); bidirectional shaping needs server cooperation that does not exist. No code merged. Spike resolved (conditional-go); status stays `backlog` — the file persists to hold this note and the implementation is parked behind its low-power-hook prerequisite, re-filing as a forward-only datagram-pacer graduation task when that lands. Adjacent-surface check: no existing shaping layer; fixed the stale `[[Epic - Control-plane hardening]]` link to the real parent.
