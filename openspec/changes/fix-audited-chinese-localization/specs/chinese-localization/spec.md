## Purpose

Present audited Chinese instructions and diagnostic results accurately while retaining existing data contracts.

## ADDED Requirements

### Requirement: REQ-ZH-MEANING — Preserve audited meanings

The app MUST correct all confirmed Chinese resource findings without removing privacy warnings, changing protocol identifiers, or changing actions.

#### Scenario: Read warnings and recovery steps

- **WHEN** a Chinese user opens support settings, export, updates, or diagnostic recovery
- **THEN** the warning and next action are Chinese and refer to the visible control names
- **AND** network authority means the target site or host

### Requirement: REQ-ZH-RESULTS — Localize known dynamic results

The app MUST localize known RKN, domain, DNS, compression, and connection-state results at the presentation boundary.

#### Scenario: Review diagnostic and history results

- **WHEN** a Chinese user views a known verdict, error, or connection state
- **THEN** user prose uses Chinese
- **AND** addresses, codec identifiers, unknown values, and machine reports remain unchanged

### Requirement: REQ-ZH-DATES — Honor selected locale in saved dates

The app MUST format remembered-network dates with the current app locale.

#### Scenario: Change app language

- **WHEN** a user changes from English to Chinese
- **THEN** the saved network timestamp is formatted in Chinese without changing its stored value

### Requirement: REQ-ZH-PARITY — Preserve resource contracts

The app MUST provide each new resource in all ten locales with compatible argument types and plural categories.

#### Scenario: Add presentation strings

- **WHEN** a known result gains a presentation resource
- **THEN** locale parity, translation export, and Android lint pass
