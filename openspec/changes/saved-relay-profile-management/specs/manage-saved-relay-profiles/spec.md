## Purpose

Users must be able to reach and safely use each saved VPN relay profile without accidentally changing a different saved profile.

## ADDED Requirements

### Requirement: REQ-RLY-1790329719797199-001 — Reach every saved profile

The VPN profile list MUST expose every saved relay profile with select and share actions. It MUST offer editing when the Mode Editor supports the profile's relay kind.

#### Scenario: More than three profiles

- **GIVEN** four saved relay profiles
- **WHEN** the user opens the VPN profile list
- **THEN** the fourth profile is reachable and its supported actions are available

#### Scenario: Imported-only relay kind

- **GIVEN** a saved profile whose relay kind has no Mode Editor form
- **WHEN** the user opens the VPN profile list
- **THEN** the profile can be selected and shared without offering an edit action

### Requirement: REQ-RLY-1790329719797199-002 — Select the chosen profile

Selecting a saved profile MUST set it as the active VPN relay without changing another saved profile.

#### Scenario: Select a different profile

- **GIVEN** two saved profiles and the first is active
- **WHEN** the user selects the second profile
- **THEN** the second profile becomes active and both saved profiles retain their data

### Requirement: REQ-RLY-1790329719797199-003 — Edit the chosen profile

Editing a saved profile MUST load its settings and credentials into the existing editor before accepting changes.

#### Scenario: Edit an inactive profile

- **GIVEN** a saved profile that is not active
- **WHEN** the user opens that profile for editing
- **THEN** the editor shows that profile's endpoint and credentials

### Requirement: REQ-RLY-1790329719797199-004 — Protect existing profile IDs

Saving a draft MUST reject a target ID that belongs to a different saved profile and MUST preserve that profile's settings and credentials.

#### Scenario: Enter another existing ID

- **GIVEN** two saved profiles with different IDs
- **WHEN** an edit of the first profile changes its ID to the second profile's ID and saves
- **THEN** the save is rejected and the second profile remains unchanged

#### Scenario: Edit an existing profile

- **GIVEN** a saved profile is open in the editor
- **WHEN** the user views its ID field
- **THEN** the ID is read only and a stale edit cannot replace later profile or credential changes

#### Scenario: Change the protocol of an existing profile

- **GIVEN** a saved profile with imported metadata and credentials
- **WHEN** the user opens it in the editor
- **THEN** the protocol choices are disabled and persistence rejects a protocol change that bypasses the UI
