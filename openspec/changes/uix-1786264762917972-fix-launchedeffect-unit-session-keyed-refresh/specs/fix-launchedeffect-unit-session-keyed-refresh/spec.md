## Purpose

Define the observable completion contract for Key session-scoped LaunchedEffect refreshes on the session id, not Unit. The 2026-06-10 Compose audit found three LaunchedEffect(Unit) sites that drive ViewModel data refresh keyed on Unit:

## ADDED Requirements

### Requirement: REQ-UIX-1786264762917972-001 — PR confirms current state at the three cited sites

The RIPDPI implementation MUST satisfy this portfolio criterion: PR confirms current state at the three cited sites.

#### Scenario: Verify criterion 1

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that PR confirms current state at the three cited sites

### Requirement: REQ-UIX-1786264762917972-002 — Each refresh LaunchedEffect keys on the data-determining argument, not Unit

The RIPDPI implementation MUST satisfy this portfolio criterion: Each refresh LaunchedEffect keys on the data-determining argument, not Unit.

#### Scenario: Verify criterion 2

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Each refresh LaunchedEffect keys on the data-determining argument, not Unit

### Requirement: REQ-UIX-1786264762917972-003 — Test (Compose/Robolectric or unit on the VM): changing the session key triggers…

The RIPDPI implementation MUST satisfy this portfolio criterion: Test (Compose/Robolectric or unit on the VM): changing the session key triggers a refresh.

#### Scenario: Verify criterion 3

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that Test (Compose/Robolectric or unit on the VM): changing the session key triggers a refresh

### Requirement: REQ-UIX-1786264762917972-004 — /gradlew :app:testDebugUnitTest --locked green; goldens unchanged

The RIPDPI implementation MUST satisfy this portfolio criterion: /gradlew :app:testDebugUnitTest --locked green; goldens unchanged.

#### Scenario: Verify criterion 4

- **WHEN** the linked change is exercised under the conditions defined by the portfolio task
- **THEN** the observed result MUST demonstrate that /gradlew :app:testDebugUnitTest --locked green; goldens unchanged
