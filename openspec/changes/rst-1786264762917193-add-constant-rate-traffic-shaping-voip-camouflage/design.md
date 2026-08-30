## Context

Portfolio task `RST-1786264762917193` owns this change. Add an outbound traffic-shaping layer that emits cooperative application records at a fixed rate and size (for example, a 200-byte record every 20 ms) regardless of payload arrival rate. The layer normalizes application-level timing and size patterns between endpoints that both implement the same codec; it does not claim lower-layer packet-boundary control.

## Goals / Non-Goals

- Goal: provide a real, reusable `AsyncRead + AsyncWrite` wrapper that paces framed records between two cooperative peers.
- Goal: keep memory bounded, apply backpressure without dropping real bytes, reject malformed framing, and expose aggregate overhead counters.
- Goal: preserve default-off behavior through a closed typed Kotlin schema.
- Non-goal: activate the wrapper for existing VLESS, MASQUE, Hysteria2, TUIC, or other relay servers that do not implement the peer codec.
- Non-goal: claim that application-level writes determine TLS, TCP, QUIC, or UDP packet boundaries on the wire.
- Non-goal: claim a mobile low-power policy integration that the relay-native control path does not expose.

## Decisions

- Treat the portfolio task as the source of priority, ownership, and lifecycle state.
- Treat this OpenSpec change as the normative behavior delta for the `rust-native` area.
- Supersede the June spike's proposed datagram-only graduation target for this
  portfolio item while retaining its warning against automatic relay activation.
- Encode every frame as a two-byte total length, a two-byte real-payload length, real bytes, and padding. Both peers select the same closed profile.
- `opus_voip` emits 200-byte frames every 20 ms. `webrtc_video` emits a deterministic 600/900/1200/900-byte cycle every 10 ms.
- Bound queued real data to 64 KiB and stop reading the application side when the queue is full so Tokio backpressure reaches the caller.
- Count real, padded/framing, and dummy-frame totals with relaxed atomics; the counters are observational and never affect output.
- Own the worker task in `ShapedStream`: shutdown confirms the outgoing peer half-close, `close()` waits for both directions, and drop aborts as a final resource-safety fallback.
- Keep implementation details in the affected modules and record exact verification evidence separately.

## Risks / Trade-offs

- Incomplete evidence could create a false close. → The archive wrapper requires all mdtask steps and evidence categories to be resolved.
- Parallel work could collide in shared contracts. → Follow the serialized-file and worktree rules in `AGENTS.md`.
- A peer without the same codec sees invalid application bytes. → The wrapper remains opt-in and is not wired into existing relay backends.
- A 10-20 ms cadence costs battery and can be distorted by Android power management. → The typed schema defaults to `off`; product activation requires a separately verified native power-policy input.
- Stream writes can be coalesced or split below this layer. → Tests and documentation assert framed-stream cadence only, never lower-layer packet cadence.

## Migration Plan

No persisted-data migration is required. Add the leaf crate and default-off Kotlin model, run the named local gates, then archive only through `taskctl` after review.
