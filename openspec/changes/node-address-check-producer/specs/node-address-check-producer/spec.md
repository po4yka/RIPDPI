## Purpose

Define the observable completion contract for Sign filtered-side node address checks for deploy admission. An operator runs a diagnostics address check from a device in the filtered network and exports one signed attestation in the deploy contract node-address-check version 1, so the deployer can admit a new node address into RIPDPI bundles.

## ADDED Requirements

### Requirement: REQ-DGN-1795868945773730-001 — The two node-address-check contract files are vendored byte-for-byte, vendore...

The RIPDPI implementation MUST satisfy this portfolio criterion: The two node-address-check contract files are vendored byte-for-byte, vendored-deploy-contracts.sha256 is updated, and the deploy contract-sync job passes.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that the two node-address-check contract files are vendored byte-for-byte, vendored-deploy-contracts.sha256 is updated, and the deploy contract-sync job passes

### Requirement: REQ-DGN-1795868945773730-002 — A diagnostics action runs a TCP connect and a TLS handshake to an operator-en...

The RIPDPI implementation MUST satisfy this portfolio criterion: A diagnostics action runs a TCP connect and a TLS handshake to an operator-entered address and port and records each probe result by stage, with ICMP recorded only as a hint.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that a diagnostics action runs a TCP connect and a TLS handshake to an operator-entered address and port and records each probe result by stage, with ICMP recorded only as a hint

### Requirement: REQ-DGN-1795868945773730-003 — The app generates one Ed25519 key per device in Android Keystore or an equiva...

The RIPDPI implementation MUST satisfy this portfolio criterion: The app generates one Ed25519 key per device in Android Keystore or an equivalent hardware-backed store, shows the public key for the operator trust file, and never exports the private key.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that the app generates one Ed25519 key per device in Android Keystore or an equivalent hardware-backed store, shows the public key for the operator trust file, and never exports the private key

### Requirement: REQ-DGN-1795868945773730-004 — The app exports one attestation file that validates against the vendored sche...

The RIPDPI implementation MUST satisfy this portfolio criterion: The app exports one attestation file that validates against the vendored schema, and a tampered record fails signature verification in a test.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that the app exports one attestation file that validates against the vendored schema, and a tampered record fails signature verification in a test

### Requirement: REQ-DGN-1795868945773730-005 — The attestation carries only the fields that the schema requires, and the act...

The RIPDPI implementation MUST satisfy this portfolio criterion: The attestation carries only the fields that the schema requires, and the action is not part of automatic diagnostics or field reports.

#### Scenario: Verify criterion 5

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that the attestation carries only the fields that the schema requires, and the action is not part of automatic diagnostics or field reports
