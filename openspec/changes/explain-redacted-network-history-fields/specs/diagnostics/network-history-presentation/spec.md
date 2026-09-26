# Network history presentation

## ADDED Requirements

### Requirement: REQ-NETWORK-HISTORY-REDACTION

The diagnostics history UI MUST distinguish identifiers that were not stored from an unknown value. It MUST show address counts from stored redacted snapshots. It MUST hide raw carrier/operator identities, Wi-Fi network IDs, private DNS hostnames, and ASN from legacy snapshots until the user shows sensitive details. Coarse radio and network type fields MUST remain visible.

#### Scenario: New redacted network snapshot

- **GIVEN** a stored snapshot with redacted identifiers and address entries
- **WHEN** the user shows sensitive details
- **THEN** the UI explains that identifiers were not stored and shows address counts

#### Scenario: Legacy cellular snapshot

- **GIVEN** a stored legacy snapshot with raw carrier/operator identities and ASN
- **WHEN** sensitive details are hidden
- **THEN** those raw values are concealed while network type remains visible
- **AND** showing sensitive details displays the legacy values

#### Scenario: Connection history snapshot

- **GIVEN** a legacy connection snapshot with raw DNS, private DNS hostname, and public IP
- **WHEN** the connection history screen shows it without a sensitive-details toggle
- **THEN** the UI shows only DNS count, coarse private DNS mode, and redacted public IP
