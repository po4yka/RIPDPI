## Purpose

The app must keep pending navigation and relock work through Activity recreation without exposing protected screens before unlock.

## ADDED Requirements

### Requirement: REQ-RESTORE-LOCKED-LAUNCH — Restore deferred navigation

The app MUST retain a pending public deep link, shared diagnostics link, or import route when Android recreates the Activity while navigation is gated, and MUST handle it after unlock.

#### Scenario: Deferred link survives recreation

- **WHEN** a link waits at the biometric screen and Android recreates the Activity
- **THEN** the link remains pending and opens only after successful unlock

### Requirement: REQ-RESTORE-RELOCK — Restore a pending relock

The app MUST retain an unhandled relock signal when Android recreates the Activity.

#### Scenario: Recreation before relock navigation

- **WHEN** the app requests relock while a protected screen is visible and Android recreates the Activity before navigation runs
- **THEN** the new Activity still navigates to the biometric screen
