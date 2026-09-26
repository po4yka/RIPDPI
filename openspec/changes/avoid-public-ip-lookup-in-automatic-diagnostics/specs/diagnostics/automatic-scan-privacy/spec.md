# Automatic scan privacy

## ADDED Requirements

### Requirement: REQ-AUTOMATIC-PUBLIC-IP

The diagnostics workflow MUST omit external public-IP lookup for pre-scan and post-scan snapshots when the scan origin is automatic background. User-initiated scans MAY retain their existing public-IP lookup behavior.

#### Scenario: Automatic scan records snapshots

- **GIVEN** an automatic background scan
- **WHEN** the workflow captures pre-scan and post-scan snapshots
- **THEN** both provider calls disable public-IP lookup

#### Scenario: User-initiated scan records snapshots

- **GIVEN** a user-initiated scan
- **WHEN** the workflow captures pre-scan and post-scan snapshots
- **THEN** both provider calls preserve public-IP lookup

#### Scenario: Automatic scan starts a DNS-corrected re-probe

- **GIVEN** an automatic background scan that requests a DNS-corrected re-probe
- **WHEN** the original and re-probe workflows capture snapshots
- **THEN** none of their provider calls request public-IP lookup
