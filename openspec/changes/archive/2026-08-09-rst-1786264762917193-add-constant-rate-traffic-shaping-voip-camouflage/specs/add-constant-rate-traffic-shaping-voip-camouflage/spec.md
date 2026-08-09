## Purpose

Define the observable completion contract for Add constant-rate traffic shaping with VoIP camouflage profile. Add an outbound traffic-shaping layer that emits packets at a fixed rate and size (e.g. 200-byte UDP every 20 ms — Opus-over-RTP shape) regardless of payload arrival rate. This defeats both inter-packet-arrival-time (IPAT) and packet-size-distribution fingerprinting that DPI uses to distinguish "bulk file transfer masquerading as VoIP" from real VoIP

## ADDED Requirements

### Requirement: REQ-RST-1786264762917193-001 — New crate ripdpi-traffic-shape with a Shaper trait that wraps any AsyncRead + A…

The RIPDPI implementation MUST satisfy this portfolio criterion: New crate ripdpi-traffic-shape with a Shaper trait that wraps any AsyncRead + AsyncWrite stream.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that New crate ripdpi-traffic-shape with a Shaper trait that wraps any AsyncRead + AsyncWrite stream

### Requirement: REQ-RST-1786264762917193-002 — At least two preset profiles: opusvoip (200-byte / 20 ms) and webrtcvideo (vari…

The RIPDPI implementation MUST satisfy this portfolio criterion: At least two preset profiles: opusvoip (200-byte / 20 ms) and webrtcvideo (variable but bounded).

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that At least two preset profiles: opusvoip (200-byte / 20 ms) and webrtcvideo (variable but bounded)

### Requirement: REQ-RST-1786264762917193-003 — Configurable via core:data:model typed schema

The RIPDPI implementation MUST satisfy this portfolio criterion: Configurable via core:data:model typed schema.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Configurable via core:data:model typed schema

### Requirement: REQ-RST-1786264762917193-004 — Unit tests verify: outgoing rate stays within ±5% of target over 1000 ticks; si…

The RIPDPI implementation MUST satisfy this portfolio criterion: Unit tests verify: outgoing rate stays within ±5% of target over 1000 ticks; size distribution is constant; reverse-path padding round-trips cleanly.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Unit tests verify: outgoing rate stays within ±5% of target over 1000 ticks; size distribution is constant; reverse-path padding round-trips cleanly

### Requirement: REQ-RST-1786264762917193-005 — Telemetry counters for bytes-padded vs bytes-real (so operators can see the ove…

The RIPDPI implementation MUST satisfy this portfolio criterion: Telemetry counters for bytes-padded vs bytes-real (so operators can see the overhead).

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Telemetry counters for bytes-padded vs bytes-real (so operators can see the overhead)
