# awg-subscription-refresh Specification

## Purpose
Keep subscription-owned AWG profiles current while preserving local client credentials and source isolation.

## Requirements

### Requirement: REQ-AWG-REFRESH-IDENTITY — Stable scoped identity

The application MUST retain an opaque profile ID for each subscription and stable member identity across refreshes and process restarts.

#### Scenario: Rotation and repeat refresh

- **WHEN** a known member changes endpoint, PSK, or cohort parameters
- **THEN** the existing row receives the changes without a duplicate

#### Scenario: Source isolation

- **WHEN** two subscriptions contain the same member label or a manual profile has that label
- **THEN** each subscription has a separate profile and the manual profile remains unchanged

### Requirement: REQ-AWG-REFRESH-KEY — Local private key protection

The application MUST retain a saved client private key when an incoming member has an empty placeholder, and MUST keep secrets outside Room JSON.

#### Scenario: User completes a template

- **WHEN** the user supplies a key and a later refresh contains a placeholder
- **THEN** the same profile keeps that key and receives new server parameters

### Requirement: REQ-AWG-REFRESH-SAFETY — Ambiguity and legacy safety

The application MUST reject duplicate member identities before updating that import and MUST NOT infer ownership of legacy rows from display names.

#### Scenario: Duplicate members

- **WHEN** a subscription contains two members with the same stable identity
- **THEN** the import fails before any AWG row changes

#### Scenario: Upgrade

- **WHEN** a saved profile has no subscription provenance
- **THEN** refresh leaves it unchanged and creates a separately owned template
