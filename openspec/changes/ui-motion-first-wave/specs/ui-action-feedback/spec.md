## Purpose

Show truthful, accessible feedback for action deadlines, diagnostics completion, and recent events in the local Android interface.

## ADDED Requirements

### Requirement: REQ-UAF-STATIC — Respect disabled animations

Shared motion consumers MUST settle immediately when animations are disabled. Reduced motion MUST remove bounce and infinite movement.

#### Scenario: Change a control with motion disabled

- **WHEN** a control changes state with animations disabled
- **THEN** its next rendered frame shows the target state without timed interpolation

### Requirement: REQ-UAF-DISCONNECT — Show the real confirmation deadline

The connection action MUST show the remaining four-second confirmation window using elapsed time. It MUST retain the existing two-press disconnect rule and allow cancellation during connection.

#### Scenario: Confirmation expires

- **WHEN** the first disconnect press is followed by no confirmation for four seconds
- **THEN** the timer disappears and the next press starts a new confirmation window without disconnecting

#### Scenario: Motion is disabled

- **WHEN** the user disables motion or requests reduced motion
- **THEN** the deadline remains four seconds and readable remaining-time feedback stays available

### Requirement: REQ-UAF-OUTCOME — Transition to actual diagnostics results

The diagnostics outcome card MUST use one finite transition from an active run to its actual result. Telemetry updates MUST NOT restart the transition. Failure and cancellation MUST NOT show success feedback.

#### Scenario: A run completes

- **WHEN** an observed active run reaches its terminal outcome
- **THEN** the card presents the actual result and available next action with a short transition, or immediately when animations are disabled

#### Scenario: Open a saved result

- **WHEN** a saved result is first displayed or a running report updates
- **THEN** the card does not replay a completion celebration

### Requirement: REQ-UAF-LATEST — Return to recent events without losing reader position

Events MUST preserve the reading position when new records arrive away from the top and MUST offer a visible Jump to latest action. Filters MUST remain unchanged.

#### Scenario: New events arrive while reading history

- **WHEN** a reader is away from the top and a new event arrives
- **THEN** the list stays in place and Jump to latest returns to the most recent visible event after an explicit press

### Requirement: REQ-UAF-ACCESS — Preserve accessible local behavior

All feedback MUST use existing theme tokens, all ten locales, readable labels, semantic actions, and usable large-font and RTL layouts. The change MUST NOT add network operations, persisted identifiers, or contract changes.

#### Scenario: Large font or RTL

- **WHEN** font scale is 2.0 or the interface uses an RTL locale
- **THEN** timer and recent-event actions remain readable and operable without overlap
