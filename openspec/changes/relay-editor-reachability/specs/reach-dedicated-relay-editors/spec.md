## Purpose

Allow users to create profiles in the existing dedicated relay editors directly from the VPN configuration screen without an external launch request.

## ADDED Requirements

### Requirement: REQ-RLY-1790339260163788-001 — Dedicated editors are reachable from VPN configuration

The VPN configuration screen MUST provide a visible manual profile action that opens each existing AnyTLS, Mieru, SSH, and AmneziaWG editor.

#### Scenario: Open each editor

- **WHEN** a user opens the manual profile menu and chooses one of the four protocols
- **THEN** the app opens the corresponding registered editor screen

### Requirement: REQ-RLY-1790339260163788-002 — Import and saved profiles remain available

The VPN configuration screen MUST retain its existing paste, scan, select, share, and supported saved-profile edit actions.

#### Scenario: Use an existing action

- **WHEN** a user chooses paste, scan, select, share, or an already supported edit action
- **THEN** the existing action is invoked with the same target and the manual profile menu does not change saved data
