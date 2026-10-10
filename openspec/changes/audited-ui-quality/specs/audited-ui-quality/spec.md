## Purpose

Repair confirmed UI defects without changing service or data contracts.

## ADDED Requirements

### Requirement: REQ-UIQ-INPUT — Input quality

The app MUST satisfy this rule: Controls have one named, stateful accessibility and keyboard target.

#### Scenario: Regression boundary

- **WHEN** A control is loading or selected
- **THEN** Its name remains available and activation dispatches only once.

### Requirement: REQ-UIQ-LAYOUT — Layout quality

The app MUST satisfy this rule: Dialog actions and section controls remain reachable at compact width and large text.

#### Scenario: Regression boundary

- **WHEN** The viewport is short or text is large
- **THEN** Content can scroll and actions do not disappear outside an unreachable region.

### Requirement: REQ-UIQ-CREDENTIAL — Credential quality

The app MUST satisfy this rule: Password fields mask their values and explicit credential display uses the existing secure-window contract.

#### Scenario: Regression boundary

- **WHEN** A user opens a credential editor or reveal surface
- **THEN** Password entry is masked and the credential surface acquires the existing secure lease.

### Requirement: REQ-UIQ-RECOVERY — Recovery quality

The app MUST satisfy this rule: Editor and refresh UI exposes real busy and failure state.

#### Scenario: Regression boundary

- **WHEN** Persistence fails or a refresh is active
- **THEN** The user can identify the failure or unavailable action and recover without a stuck editor.

### Requirement: REQ-UIQ-EVIDENCE — Evidence quality

The app MUST satisfy this rule: Diagnostic feedback follows the current profile, run, and measured evidence.

#### Scenario: Regression boundary

- **WHEN** A user selects a profile with no result or starts a new tuner run
- **THEN** No other profile result or stale success message is presented as current evidence.

### Requirement: REQ-UIQ-LOCALE — Locale quality

The app MUST satisfy this rule: Visible tool states use localized resources.

#### Scenario: Regression boundary

- **WHEN** The app uses a supported locale
- **THEN** Tool state labels use that locale rather than raw English enum names.

