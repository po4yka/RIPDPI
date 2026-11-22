## ADDED Requirements

### Requirement: REQ-DGN-1795338086230261-001 — Compare one working direct domain

Home diagnostics SHALL include one non-control domain with a successful HTTP response from a completed direct scan in the existing comparison budget. Home SHALL use the same domain target on the active route.

#### Scenario: The body stops on the active route

- **WHEN** a direct HTTP response completes and the corresponding active-route HTTP response does not complete
- **THEN** Home reports an active-path regression even if the separate TLS checks pass

### Requirement: REQ-DGN-1795338086230261-002 — Use complete HTTP control evidence

Home diagnostics SHALL require a complete direct report to select path-comparison targets. A direct control SHALL have a successful HTTP response.

#### Scenario: A control completes TLS alone

- **WHEN** the control has only TLS success
- **THEN** the control does not supply an HTTP baseline for path comparison

#### Scenario: The direct report is partial

- **WHEN** the direct report is partial
- **THEN** Home does not use it to select path-comparison targets
