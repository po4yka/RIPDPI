# m3-home-hierarchy Specification

## Purpose
Define the Home screen hierarchy for status and notices. Portfolio task: `UIX-1793534654918156`.

## Requirements

### Requirement: REQ-UIX-1793534654918156-001 — Status hero first

The Home screen MUST show a status hero with connection state, duration, and strategy as its first element on the phone.

#### Scenario: Disconnected phone

- **WHEN** Home opens on a phone in the disconnected state
- **THEN** the status hero is the first element and notices show as a compact banner or chip

### Requirement: REQ-UIX-1793534654918156-002 — Connected status stays visible

In the connected state, the status hero MUST be fully visible on the first screen without scrolling.

#### Scenario: Connected phone

- **WHEN** Home shows the connected state on a phone
- **THEN** the state, duration, and strategy are all visible without scrolling

### Requirement: REQ-UIX-1793534654918156-003 — Tablet layout has no dead area

On the tablet, the primary button width MUST be capped, the columns MUST line up, and the screen MUST NOT leave a half-empty area.

#### Scenario: Tablet Home

- **WHEN** Home opens on a tablet
- **THEN** the Connect button is not wider than its cap, the columns align, and the content fills the layout

### Requirement: REQ-UIX-1793534654918156-004 — Goldens record the new layout

The phone and tablet Roborazzi goldens for Home MUST show the new layout. They MUST be recorded and blessed with owner approval.

#### Scenario: Golden verification

- **WHEN** `verifyRoborazzi` runs on the Home screens
- **THEN** it passes against goldens that show the new layout

### Requirement: REQ-UIX-1793534654918156-005 — Layout is confirmed on device

A device capture MUST confirm the phone and tablet layouts in the disconnected and connected states.

#### Scenario: Device capture

- **WHEN** Home is captured on a phone and a tablet in both states
- **THEN** each capture shows the status first and no dead area
