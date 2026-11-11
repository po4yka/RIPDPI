## Purpose

Keep native Vision traffic protected until Direct is valid and preserve stream data and errors at framing boundaries.

## ADDED Requirements

### Requirement: REQ-VISION-TLS — Validate inner TLS before Direct

The stream MUST allow Direct only after a complete valid TLS 1.3 ServerHello with a supported cipher other than CCM_8. It MUST handle handshake fragmentation across reads and records.

#### Scenario: Marker outside extensions

- **WHEN** a TLS 1.2 ServerHello has a TLS 1.3 marker in Random or another field
- **THEN** uplink padding ends with End and retains outer TLS.

#### Scenario: Fragmented TLS 1.3

- **WHEN** a valid TLS 1.3 ServerHello spans records and reads
- **THEN** the stream can use Direct after a complete uplink application record.

### Requirement: REQ-VISION-EOF — Report truncated frames

The stream MUST return UnexpectedEof for an incomplete recognized Vision header, content or padding. EOF at a complete chunk boundary MUST remain valid.

#### Scenario: Partial content

- **WHEN** a recognized chunk declares four content bytes but ends after two
- **THEN** reading reports truncation after any available payload.

### Requirement: REQ-VISION-FALLBACK — Preserve unpadded responses

The stream MUST preserve unpadded replies shorter than the UUID and return mismatching prefixes without waiting for more bytes.

#### Scenario: Short reply

- **WHEN** the peer sends OK without padding and closes
- **THEN** the caller receives both bytes before EOF.

### Requirement: REQ-VISION-LIFECYCLE — Keep framing and stream termination consistent

The stream MUST apply an explicit command policy and keep valid half-close and cancellation behavior across independent direction transitions. Tests MUST verify byte delivery and bounded cleanup, not just method calls.

#### Scenario: Independent direction transition

- **WHEN** one direction has entered Direct and the other direction closes
- **THEN** the peer receives valid framing and any pending payload, and the remaining direction can finish.
