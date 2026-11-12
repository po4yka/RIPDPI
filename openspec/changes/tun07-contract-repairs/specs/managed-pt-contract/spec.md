## Purpose

Define reliable local contracts for Tor pluggable transport integration without claiming unobserved external compatibility.

## ADDED Requirements

### Requirement: REQ-TUN07-RUN — Isolate helper output per run

The implementation MUST prevent output from a stopped helper from changing parser or runtime state for a later run.

#### Scenario: Drop stopped helper output

- **WHEN** a stopped helper reader resumes after a later helper has started
- **THEN** its pending output cannot change the later helper's negotiation or failure state.

### Requirement: REQ-TUN07-CERT — Validate certificate format

The implementation MUST reject an obfs4 certificate with invalid unpadded standard Base64 encoding or a decoded length other than 52 bytes before Tor activation. The helper MUST retain its own certificate and authentication checks.

#### Scenario: Reject malformed certificate

- **WHEN** a Tor obfs4 bridge contains an empty, padded, malformed or wrong-length certificate
- **THEN** profile validation fails before the helper starts.

### Requirement: REQ-TUN07-RELAY — Reject standalone obfs4

The implementation MUST reject standalone obfs4 relay activation and retain Tor with obfs4 transport.

#### Scenario: Reject standalone obfs4

- **WHEN** a user selects a legacy standalone obfs4 profile
- **THEN** activation fails with guidance to select Tor; no PT process starts.

### Requirement: REQ-TUN07-IDENTITY — Preserve Tor identities

The implementation MUST check the bridge endpoint without reinterpreting valid Arti identities.

#### Scenario: Preserve Tor identities

- **WHEN** a validated bridge contains RSA and Ed25519 identities or named base64 RSA
- **THEN** permission checks use its endpoint and leave the bridge line unchanged.

### Requirement: REQ-TUN07-STARTUP — Complete managed negotiation

The implementation MUST report readiness only after VERSION 1, a matching numeric loopback SOCKS5 listener, and CMETHODS DONE; retain startup errors and bound control lines.

#### Scenario: Complete managed negotiation

- **WHEN** the helper has advertised a listener but has not completed negotiation
- **THEN** the runtime remains unready.

### Requirement: REQ-TUN07-STOP — Close socket ownership

The implementation MUST reject new socket admission after close and close all sockets already owned by the adapter.

#### Scenario: Close socket ownership

- **WHEN** stop overlaps a connection handshake or active stream
- **THEN** owned sockets close and the stopped adapter cannot accept a new flow.

### Requirement: REQ-TUN07-ARGS — Escape PT arguments

The implementation MUST escape backslash, semicolon and equals in PT parameter keys and values.

#### Scenario: Escape PT arguments

- **WHEN** a parameter value contains a reserved separator
- **THEN** encoding preserves its value after a compliant decoder processes it.

### Requirement: REQ-TUN07-EVIDENCE — Separate artifact evidence

The implementation MUST distinguish source registration, host tests, Android artifact receipts and external compatibility.

#### Scenario: Separate artifact evidence

- **WHEN** only host tests and source registration are available
- **THEN** verification does not claim Android or external compatibility.
