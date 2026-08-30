# Change: Add constant-rate traffic shaping with VoIP camouflage profile

Task ID: `RST-1786264762917193`

## Why

Add an outbound traffic-shaping layer that emits cooperative application records at a fixed rate and size (for example, a 200-byte record every 20 ms) regardless of payload arrival rate. The change gives RIPDPI a measurable, default-off research component for normalizing application-level timing and size patterns between endpoints that both implement the same codec.

## What Changes

- Add a reusable Rust framed-stream shaper for two cooperative endpoints.
- Provide the `opus_voip` fixed-size preset and a bounded variable-size `webrtc_video` preset.
- Add a default-off typed Kotlin configuration model and lock-free aggregate overhead counters.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `add-constant-rate-traffic-shaping-voip-camouflage`: Add constant-rate traffic shaping with VoIP camouflage profile

### Modified Capabilities

- None.

## Impact

- Portfolio area: `rust-native`.
- The crate is opt-in and is not inserted into existing relay clients because deployed peers do not decode its framing.
- The stream wrapper controls application writes, not lower-layer TLS, TCP, or QUIC packetization; no on-wire packet-boundary claim is made.
