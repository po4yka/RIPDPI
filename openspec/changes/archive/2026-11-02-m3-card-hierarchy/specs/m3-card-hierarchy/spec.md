## Purpose

Define one card rule so primary and secondary content differ in visual weight. Portfolio task: `UIX-1793534441702605`.

## ADDED Requirements

### Requirement: REQ-UIX-1793534441702605-001 — One documented card rule

The design documents MUST state one card rule. `surfaceContainer` tonal steps group content. An outline marks only interactive or selected cards. A shadow marks only hero or selected cards.

#### Scenario: Read the rule

- **WHEN** a contributor reads `DESIGN.md` and `docs/design-system.md`
- **THEN** both name the same rule and the card role for grouping, interactive or selected, and hero use

### Requirement: REQ-UIX-1793534441702605-002 — Call sites follow the rule

Every call site listed in the portfolio task MUST follow the card rule. Ordinary content MUST NOT use the Elevated or Status card role. The `RipDpiRemediationCard` default MUST NOT add an outline or shadow.

#### Scenario: Ordinary content card

- **WHEN** a screen shows a non-interactive, non-selected card of ordinary content
- **THEN** the card uses a tonal fill with no outline and no shadow

#### Scenario: Selected card

- **WHEN** a card is selected
- **THEN** the card shows the outline or shadow that the rule allows for selection

### Requirement: REQ-UIX-1793534441702605-003 — Goldens record the new look

The Roborazzi goldens for the changed screens MUST show the new card treatment. They MUST be recorded and blessed with owner approval.

#### Scenario: Golden verification

- **WHEN** `verifyRoborazzi` runs on the changed screens
- **THEN** it passes against goldens that show the new card treatment

### Requirement: REQ-UIX-1793534441702605-004 — Hierarchy is visible on device

On a device, primary content on Home, Diagnostics, and Settings MUST look heavier than secondary content.

#### Scenario: Device capture

- **WHEN** Home, Diagnostics, and Settings are captured on a device
- **THEN** primary and secondary cards are visibly different in weight
