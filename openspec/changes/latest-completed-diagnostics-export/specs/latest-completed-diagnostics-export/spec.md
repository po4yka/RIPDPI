## ADDED Requirements

### Requirement: REQ-DGN-1795345317475114-001 — Select an automatic archive session by completion

An automatic diagnostics archive SHALL select the latest completed session by completion time across retained history. Selection SHALL remain independent of full report availability.

#### Scenario: A running scan has a staged report

- **WHEN** a running scan has a staged report
- **THEN** the automatic archive uses the last completed session or exports live state if no completed session exists

#### Scenario: The latest full report exceeds the storage limit

- **WHEN** the last completed session has saved probes and its full report exceeds the storage limit
- **THEN** the archive includes that session and its saved probe results and records that the full report is unavailable
