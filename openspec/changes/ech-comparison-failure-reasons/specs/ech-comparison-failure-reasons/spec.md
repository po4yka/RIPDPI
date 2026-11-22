## ADDED Requirements

### Requirement: REQ-DGN-1795351267598237-001 — Keep ECH comparison failure evidence

The comparison SHALL distinguish no published ECH configuration, DNS lookup failure, unusable ECH configuration, and authenticated ECH rejection in the card, history, and redacted archive. The comparison SHALL retain post-handshake transfer outcomes and run at most one attempt for each of its three modes.

#### Scenario: The server rejects ECH

- **WHEN** the TLS implementation reports authenticated ECH rejection
- **THEN** the real ECH row records rejection and does not start a retry

### Requirement: REQ-DGN-1795351267598237-002 — Use the selected port for ECH discovery

The comparison SHALL accept an otherwise compatible HTTPS record with an explicit port equal to the selected endpoint port. A different port SHALL NOT change the pinned endpoint.

#### Scenario: HTTPS records specify different ports

- **WHEN** an otherwise compatible HTTPS record specifies the selected port
- **THEN** its ECH configuration can be used on the pinned endpoint
