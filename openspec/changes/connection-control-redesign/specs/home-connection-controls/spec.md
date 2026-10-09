## Purpose

Give home-screen users clear connection actions and route information without presenting elapsed time as measured connection evidence.

## ADDED Requirements

### Requirement: REQ-HCC-ACTION — Visible primary action

The component MUST keep its available primary action visible and readable in each connection state.

#### Scenario: Connection is in progress

- **WHEN** the connection is being established
- **THEN** the cancel action remains visible without a drag gesture.

#### Scenario: Connection has failed

- **WHEN** the connection reports an error
- **THEN** the retry action and the error detail are visible as separate items.

### Requirement: REQ-HCC-ROUTE — Separate route information

The component MUST display route information as read-only content outside the primary action target and MUST expose that information to accessibility services.

#### Scenario: Direct route

- **WHEN** the route is direct
- **THEN** the route label is visible and cannot be mistaken for a route-selection button.

### Requirement: REQ-HCC-EVIDENCE — Honest progress

The component MUST NOT mark a connection stage complete only because time has passed.

#### Scenario: Connection has no stage evidence

- **WHEN** connection establishment has been pending for five seconds without stage completion events
- **THEN** the component shows a waiting state without completed stage check marks.

### Requirement: REQ-HCC-INPUT — Accessible and safe actions

The component MUST support touch, keyboard, and accessibility activation without requiring a drag. It MUST announce disabled actions and MUST preserve the existing disconnect guard where it applies.

#### Scenario: Android lockdown prevents disconnect

- **WHEN** Android lockdown blocks disconnection
- **THEN** the action is disabled, its reason is shown, and accessibility services can identify its unavailable state.

#### Scenario: Confirm disconnection

- **WHEN** a user requests a guarded disconnection
- **THEN** the component shows the confirmation action before it stops the connection.

### Requirement: REQ-HCC-ADAPT — Adaptive presentation

The component MUST keep the action and status readable at large font scales and compact widths, in light and dark themes, and in RTL locales.

#### Scenario: Large text in a compact RTL layout

- **WHEN** the available width is 320dp and the font scale is 2.0 in an RTL locale
- **THEN** the action label and route information remain readable without overlap or a clipped interaction target.

### Requirement: REQ-HCC-RESEARCH — Traceable design references

The design record MUST identify the Mobbin MCP screens inspected and distinguish those references from local design decisions.

#### Scenario: Mobbin MCP is unavailable

- **WHEN** the requested integration cannot be accessed
- **THEN** the reference research is recorded as pending and no Mobbin-based design conclusion is claimed.
