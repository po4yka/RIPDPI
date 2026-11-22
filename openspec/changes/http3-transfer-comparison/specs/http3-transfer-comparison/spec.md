## ADDED Requirements

### Requirement: REQ-DGN-1795357427018830-001 — Run a verified pair on one direct endpoint

The manual profile SHALL require RAW and one explicit destination IP. It SHALL request the same object and port over HTTPS/TCP and HTTP/3. The two requests SHALL share a deadline of at most 15 seconds. Each successful verification SHALL require a complete response equal to the requested size and SHA-256. The allowed object size is 1 to 32768 bytes.

#### Scenario: Both responses match

- **WHEN** both protocols complete the expected response
- **THEN** the report records both as verified with their byte counts

#### Scenario: The HTTP/3 body stops after headers

- **WHEN** HTTPS/TCP completes and HTTP/3 stops during the body
- **THEN** the report indicates a body-stage difference and keeps the received byte counts

### Requirement: REQ-DGN-1795357427018830-002 — Present comparable evidence

The manual result and its saved view SHALL show the outcome, stage, and received byte count for each protocol. The result SHALL show the first differing stage for comparable evidence. Missing evidence or a changed network scope SHALL make the comparison inconclusive.

#### Scenario: The network changes during the pair

- **WHEN** the pair loses original network authority
- **THEN** the result is inconclusive and keeps the measured byte counts
