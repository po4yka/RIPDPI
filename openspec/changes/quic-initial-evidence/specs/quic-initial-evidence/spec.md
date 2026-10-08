## Purpose

Expose the precise protocol stage tested by the QUIC connectivity probe on all outcomes.

## ADDED Requirements

### Requirement: REQ-QUIC-INITIAL-SCOPE — Publish bounded probe scope

The implementation MUST report measurementScope=quic_initial_response and http3Validated=false for the QUIC Initial connectivity probe.

#### Scenario: Valid or invalid response

- **WHEN** a server returns a valid QUIC response or an invalid reflected packet
- **THEN** the result retains its existing outcome and declares the same bounded measurement scope.

#### Scenario: Privacy and compatibility

- **WHEN** a result is exported
- **THEN** the additive detail keys contain no personal identifiers and existing outcome codes remain unchanged.
