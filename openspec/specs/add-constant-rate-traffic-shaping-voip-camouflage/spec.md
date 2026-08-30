# add-constant-rate-traffic-shaping-voip-camouflage Specification

## Purpose
Define the observable completion contract for Add constant-rate traffic shaping with VoIP camouflage profile. The change adds an outbound traffic-shaping layer that emits cooperative application records at a fixed rate and size (for example, a 200-byte record every 20 ms) regardless of payload arrival rate. Both endpoints must implement the same codec; the contract is application-level framing, not a claim about TLS, TCP, QUIC, or UDP packet boundaries.

## Requirements

### Requirement: REQ-RST-1786264762917193-001 — Cooperative stream wrapper

The implementation MUST provide a `ripdpi-traffic-shape` crate whose `Shaper` trait wraps any owned `AsyncRead + AsyncWrite + Unpin + Send + 'static` stream on a Tokio runtime. Both endpoints MUST use the same framed codec, and the API MUST remain opt-in.

#### Scenario: Verify criterion 1

- **WHEN** two wrappers are connected through an in-memory stream
- **THEN** real bytes written at one endpoint MUST be recovered unchanged at the other endpoint
- **AND** malformed or truncated framing MUST fail instead of being treated as clean EOF

### Requirement: REQ-RST-1786264762917193-002 — Closed bounded profiles

The implementation MUST expose `opus_voip` at 200 bytes / 20 ms and `webrtc_video` as a deterministic variable-size profile bounded to 600..=1200 bytes / 10 ms.

#### Scenario: Verify criterion 2

- **WHEN** four `webrtc_video` ticks are observed
- **THEN** their framed sizes MUST be 600, 900, 1200, and 900 bytes
- **AND** `opus_voip` frames MUST remain exactly 200 bytes

### Requirement: REQ-RST-1786264762917193-003 — Default-off typed schema

`core:data:model` MUST provide a serializable closed `TrafficShapeProfile` with stable `off`, `opus_voip`, and `webrtc_video` identifiers plus their declared size and interval bounds. The default configuration MUST be `off`.

#### Scenario: Verify criterion 3

- **WHEN** a default `TrafficShapeConfig` is constructed
- **THEN** shaping MUST be disabled
- **AND** serialization MUST preserve the three stable identifiers

### Requirement: REQ-RST-1786264762917193-004 — Deterministic pacing and bounded backpressure

Tests MUST verify the 1,000-tick Opus interval within ±5%, fixed 200-byte framed size, reverse-path round-trip, and lossless backpressure for a payload larger than the internal queue.

#### Scenario: Verify criterion 4

- **WHEN** Tokio virtual time drives 1,000 `opus_voip` ticks
- **THEN** elapsed monotonic time MUST remain within ±5% of 20 seconds
- **AND** every framed record MUST be 200 bytes
- **AND** payloads larger than 64 KiB MUST round-trip without truncation

### Requirement: REQ-RST-1786264762917193-005 — Aggregate overhead telemetry

Each shaped endpoint MUST expose lock-free aggregate counters for transmitted/received real bytes, transmitted/received framing-plus-padding bytes, and transmitted dummy frames. No payload bytes or peer identifiers may enter telemetry.

#### Scenario: Verify criterion 5

- **WHEN** a profile cycle carries real data and dummy frames
- **THEN** the snapshot MUST report exact real, padded, and dummy totals
- **AND** reading telemetry MUST NOT change emitted bytes
