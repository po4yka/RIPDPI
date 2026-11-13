## Purpose

Keep TLS record rewriting within the existing configuration, envelope, and payload safety contracts.

## ADDED Requirements

### Requirement: REQ-TLS-RANDREC-CONFIG — Validate native fragment settings

The native converter MUST normalize legacy defaults and then enforce count 2..16 and sizes 1..4096 with minimum no greater than maximum for primary and rotation chains.

#### Scenario: Invalid positive fragment settings

- **WHEN** native JSON declares count 17, size 4097, or minimum 80 and maximum 16
- **THEN** conversion fails before any payload is emitted

#### Scenario: Legacy default fields

- **WHEN** a tlsrandrec field is absent or nonpositive
- **THEN** the converter retains the existing default normalization before validation

### Requirement: REQ-TLS-PRELUDE-ENVELOPE — Require a handshake envelope

TLS prelude normalization MUST reject non-handshake content types and invalid major versions without changing the input. It MUST preserve supported record minor overrides and truncated fake templates.

#### Scenario: Non-handshake body resembles ClientHello

- **WHEN** a record with ContentType 0x17 contains ClientHello-like bytes
- **THEN** the prelude leaves the bytes unchanged and reports no applied TLS prelude

#### Scenario: Valid real or truncated fake input

- **WHEN** the input has the supported handshake envelope and satisfies the path's completeness rules
- **THEN** record framing preserves the handshake payload and trailing records

### Requirement: REQ-TLS-RANDREC-ARITHMETIC — Bound partition arithmetic

Random-record partition calculations MUST handle representable direct API size bounds without arithmetic overflow and without producing empty fragments.

#### Scenario: Large direct API size limit

- **WHEN** a feasible small tail uses a maximum size near the machine integer limit
- **THEN** partitioning terminates with a valid payload-preserving partition

### Requirement: REQ-TLS-RANDREC-ADAPTIVE — Preserve saved size bounds

Adaptive TLS random-record profiles MUST keep all tail fragment sizes within the saved minimum and maximum while retaining feasibility checks.

#### Scenario: Tight or wide profile

- **WHEN** an adaptive profile adjusts a configured tail layout
- **THEN** every emitted tail record remains within the saved size interval or the prelude safely skips an infeasible layout
