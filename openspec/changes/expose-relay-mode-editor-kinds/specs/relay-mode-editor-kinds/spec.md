## Purpose

Mode Editor lets users configure every supported relay kind and safely update imported profiles without losing parameters or credentials.

## ADDED Requirements

### Requirement: REQ-RLY-1790340340749310-001 — Complete kind forms

The Mode Editor MUST expose editable fields needed for VLESS TLS/xHTTP, Trojan, Shadowsocks, Google Apps Script, Mieru, and SSH profiles.

#### Scenario: Create a supported relay profile

- **WHEN** a user selects one of the six kinds and enters valid connection settings
- **THEN** the editor saves a profile and credentials usable by the existing relay runtime

### Requirement: REQ-RLY-1790340340749310-002 — Lossless profile update

The Mode Editor MUST load and preserve every imported profile field and credential during an edit unless the user changes that field.

#### Scenario: Update an imported profile

- **WHEN** a user changes one field of an imported profile and saves
- **THEN** its other configuration and credentials remain unchanged, including fields not exposed by the editor

### Requirement: REQ-RLY-1790340340749310-003 — Reject incomplete profiles

The Mode Editor MUST reject missing credentials, invalid endpoint values, and unsupported kind-specific combinations before saving.

#### Scenario: Missing required secret

- **WHEN** a user tries to save a profile without the required password, key, or UUID
- **THEN** the editor shows a validation error and does not replace the saved profile

### Requirement: REQ-RLY-1790340340749310-004 — Preserve saved identity

The Mode Editor MUST preserve the saved profile ID and kind during an edit and reject a stale save when the underlying profile changed concurrently.

#### Scenario: Concurrent profile update

- **WHEN** an existing profile changes after the editor opens and the user saves stale edits
- **THEN** the save is rejected without overwriting the latest profile
