## Purpose

Define the observable completion contract for Require a volume-moving transfer for the connection test verdict. A connection test that reports Passed has moved a payload larger than the TCP-freeze threshold and checked it byte-exact, not only a handshake or a small request.

## ADDED Requirements

### Requirement: REQ-DGN-1795704789266331-001 — The connection test moves a payload larger than the TCP-freeze threshold throug...

The RIPDPI implementation MUST satisfy this portfolio criterion: The connection test moves a payload larger than the TCP-freeze threshold through the active route and checks its length and hash against the expected values of the operator-owned payload controls.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that the connection test moves a payload larger than the TCP-freeze threshold through the active route and checks its length and hash against the expected values of the operator-owned payload controls

### Requirement: REQ-DGN-1795704789266331-002 — A test that completes the handshake and a small request but stalls before the f...

The RIPDPI implementation MUST satisfy this portfolio criterion: A test that completes the handshake and a small request but stalls before the full payload does not report Passed and records the byte count where the transfer stopped.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that a test that completes the handshake and a small request but stalls before the full payload does not report Passed and records the byte count where the transfer stopped

### Requirement: REQ-DGN-1795704789266331-003 — The test adds no third-party host. When the server publishes no payload control...

The RIPDPI implementation MUST satisfy this portfolio criterion: The test adds no third-party host. When the server publishes no payload control, the verdict states that volume was not tested instead of Passed.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that the test adds no third-party host. When the server publishes no payload control, the verdict states that volume was not tested instead of Passed

### Requirement: REQ-DGN-1795704789266331-004 — The test reuses the existing transfer measurement and stop evidence and adds no...

The RIPDPI implementation MUST satisfy this portfolio criterion: The test reuses the existing transfer measurement and stop evidence and adds no second transfer engine.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that the test reuses the existing transfer measurement and stop evidence and adds no second transfer engine
