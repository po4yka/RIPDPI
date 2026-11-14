# quic-sni-prelude-safety Specification

## Purpose
Bound optional QUIC SNI split resource use and preserve the original payload when optional work fails.

## Requirements

### Requirement: REQ-DPI18-BUDGET — Bound repetitions

Configuration MUST reject individual and aggregate DPI-18 counts above 64, including CLI flags. The planner MUST allocate at most 64 copies for a direct Rust model. Count zero MUST remain a no-op.

#### Scenario: Excessive count

- **WHEN** a count or sum exceeds 64
- **THEN** configuration validation rejects it and direct-model planning stays bounded

### Requirement: REQ-DPI18-SIZE — Bound optional size

An optional rebuilt packet MUST NOT exceed input size or the supported UDP limit.

#### Scenario: Packet growth

- **WHEN** framing exceeds the size budget
- **THEN** the planner skips the optional packet and retains the original

### Requirement: REQ-DPI18-SEND — Preserve required sends

A recoverable local send error on an optional ordinary write MUST NOT prevent a later original write. Other flow errors MUST remain errors. Required TTL restoration and original-send failures MUST remain errors. Fallback MUST NOT count as completed optional work.

#### Scenario: Optional failure

- **WHEN** an optional write has a local send error before the original
- **THEN** execution restores TTL and attempts the original once with fallback evidence

### Requirement: REQ-DPI18-SCOPE — Preserve existing boundaries

The change MUST preserve activation, single-datagram normalization, ECH Outer bytes, non-root execution and relay skip. It MUST NOT log secrets.

#### Scenario: Existing input

- **WHEN** input is within the existing supported scope
- **THEN** its behavior remains available within the resource budget
