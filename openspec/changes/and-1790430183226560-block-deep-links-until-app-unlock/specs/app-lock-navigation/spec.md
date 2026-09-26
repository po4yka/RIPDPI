## Purpose

Protect locally locked or not yet onboarded app content from all inbound deep-link routes while preserving the requested destination for authorized use.

## ADDED Requirements

### Requirement: REQ-APP-LOCK-NAVIGATION-GATE — Gate inbound destinations

The app MUST keep onboarding and local authentication screens in front of every inbound deep-link destination until that gate has cleared.

#### Scenario: Link arrives while authentication is required

- **WHEN** a supported deep link starts or resumes the app with local authentication required
- **THEN** the app shows the authentication gate and does not show the linked content

#### Scenario: Link arrives before onboarding is complete

- **WHEN** a supported deep link starts or resumes the app before onboarding is complete
- **THEN** the app shows onboarding and does not show the linked content

### Requirement: REQ-APP-LOCK-NAVIGATION-RESUME — Preserve link intent

The app MUST open a supported pending deep-link destination after the gate clears, without executing connection start or stop actions from a public link.

#### Scenario: User authenticates

- **WHEN** a user completes local authentication after opening a supported deep link
- **THEN** the app opens the requested destination

#### Scenario: Public disconnect link

- **WHEN** a public disconnect deep link opens the app
- **THEN** the app does not stop the configured connection
