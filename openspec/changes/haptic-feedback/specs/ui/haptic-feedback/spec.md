## Purpose

Provide user-controlled haptic feedback for actual operation results without replay from stored or rendered UI state.

## ADDED Requirements

### Requirement: REQ-HAPTIC-PREFERENCE — Persist the user choice

The app MUST expose System default and Off in Full and Simple. Missing protobuf or JSON values MUST retain system behavior. Off MUST suppress all app feedback and persist through restart. The app MUST honor Android touch feedback settings and MUST NOT enable system vibration or require a new permission.

#### Scenario: Upgrade and opt out

- **WHEN** legacy settings omit the new field
- **THEN** feedback follows the system; after selecting Off and restarting it remains disabled.

### Requirement: REQ-HAPTIC-CONNECTION — Signal actual connection results

The app MUST signal success at most once for a current user start that reaches actual Running. Acceptance, pending state, restored Running, telemetry updates and cancellation MUST NOT signal success. A failed start MUST retain a single error signal and recovery message.

#### Scenario: Delayed result and repeated status

- **WHEN** a user start is accepted and later reaches Running with repeated status updates
- **THEN** acceptance is quiet and actual completion emits one success.

#### Scenario: Cancel or restored state

- **WHEN** the start is cancelled or a screen attaches to an already-running service
- **THEN** no success is synthesized from the snapshot.

### Requirement: REQ-HAPTIC-RESULT — Deliver each check result once

Detection and diagnostics MUST deliver feedback from operation events. Rendering a retained result, navigation, recomposition and Activity recreation MUST NOT replay consumed feedback. Consecutive completed runs with equal results MUST each deliver one event. Cancellation MUST remain quiet. Disabled feedback MUST be consumed without later replay.

#### Scenario: Equal results and screen return

- **WHEN** two separate checks finish with the same verdict and the user returns to the result screen
- **THEN** each run signals once and returning adds no signal.

#### Scenario: Opt out during completion

- **WHEN** an event is consumed while app feedback is Off and the user later enables it
- **THEN** the old event is not replayed.

### Requirement: REQ-HAPTIC-VALIDATION — Preserve accessible offline behavior

The preference MUST have readable labels, selected-value semantics, ten-locale coverage, and usable large-font and RTL layout. FULL backup MUST preserve the choice; SHARE MUST continue to omit settings. Feedback MUST remain supplemental to visible text. Device acceptance MUST distinguish observed event calls from physical actuator quality.

#### Scenario: Accessible choice and backup

- **WHEN** the user reads the setting with a large font or restores a FULL backup
- **THEN** both choices remain reachable and the saved choice is retained without exporting secrets.
