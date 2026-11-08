## ADDED Requirements

### Requirement: REQ-LOC05-REUSE — Retain compatible upstream after payload routing

The implementation MUST retain an established TCP upstream when payload routing
changes only the selected strategy group or host metadata and the effective
connection policy, egress and preferred destination set remain equal. It MUST
use the selected payload group for subsequent relay processing.

#### Scenario: TLS group on one accepted connection

- **WHEN** delayed HTTP CONNECT succeeds and a TLS ClientHello selects another
  compatible group for an origin that accepts only one TCP connection
- **THEN** certificate verification and application exchange succeed on that
  accepted connection with the selected payload strategy.

#### Scenario: Connection policy changes

- **WHEN** payload routing selects a different upstream or connection policy,
  or requires payload in the SYN
- **THEN** the provisional socket is replaced before application bytes are sent
  and the selected connection policy is applied.

#### Scenario: Payload policy blocks the destination

- **WHEN** payload routing blocks the destination after CONNECT success
- **THEN** the provisional socket receives no application bytes and the tunnel
  closes without another HTTP response.
