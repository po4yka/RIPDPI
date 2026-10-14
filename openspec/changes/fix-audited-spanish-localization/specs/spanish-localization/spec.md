## Purpose

Spanish users can understand audited warnings, actions, diagnostic results, and visible numbers without a change to network or storage behavior.

## ADDED Requirements

### Requirement: REQ-ES-MEANING — Correct audited resource meanings

The app MUST correct all 125 active and four unused confirmed resource findings, preserve credential and export warnings, and use visible action names consistently.

#### Scenario: Review a sensitive action in Spanish

- **WHEN** Spanish is selected and the user reviews support settings, permissions, PCAP, or an export
- **THEN** the text describes the original privacy and retention boundaries in Spanish
- **AND** recovery instructions identify the actual visible action.

### Requirement: REQ-ES-RESULTS — Localize known diagnostic presentation

The app MUST localize known DoQ, DoH bootstrap, BYOH, SNI, and network snapshot labels while preserving raw evidence, protocol tokens, and unknown values.

#### Scenario: Show known results and custom evidence

- **WHEN** a diagnostic result or history snapshot is shown in Spanish
- **THEN** known labels use Spanish resources
- **AND** unknown values and stored result codes remain unchanged.

### Requirement: REQ-ES-NUMBERS — Use selected locale for visible numbers

The app and service MUST format diagnostic and notification numbers with the selected app locale without changing technical units or exported formats.

#### Scenario: Select Spanish while the system language is English

- **WHEN** the app language is Spanish and a fractional traffic value is shown
- **THEN** the visible number uses the Spanish decimal separator
- **AND** switching back to English restores the English separator.

### Requirement: REQ-ES-PARITY — Preserve all locales and contracts

The change MUST preserve resource key, type, argument, and native display name contracts across all ten locales.

#### Scenario: Add a diagnostic label

- **WHEN** a new translatable presentation key is added
- **THEN** every shipped locale contains it in the same source commit
- **AND** lint and resource regression checks pass without baseline changes.
