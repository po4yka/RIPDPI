# ui/m3-component-facade Specification

## Purpose
Build every shared RipDpi component on its Material 3 counterpart behind an unchanged facade, and delete the RDS layers that Material 3 now covers.

## Requirements

### Requirement: REQ-UIX-M3C-FACADE — Material 3 inside the facade

Every shared RipDpi component that has a Material 3 counterpart MUST render that counterpart. Its public signature, callbacks, semantics, and test tags MUST stay the same unless all call sites change in the same commit.

#### Scenario: Material 3 inside the facade

- **WHEN** a screen calls a RipDpi component
- **THEN** a Material 3 component renders with the same semantics and test tag

### Requirement: REQ-UIX-M3C-DELETE — Covered RDS layers removed

RDS state tokens, surface tokens, and CommonStyle recipes that Material 3 component defaults cover MUST be deleted.

#### Scenario: Covered RDS layers removed

- **WHEN** the theme package is inspected
- **THEN** no covered state, surface, or style layer remains

### Requirement: REQ-UIX-M3C-CUSTOM — Custom components on Material tokens

The connection actuator, route diagrams, telemetry plots, and log rows MUST keep their behavior and MUST read colors, type, shape, and motion from the Material 3 theme.

#### Scenario: Custom components on Material tokens

- **WHEN** the theme changes contrast or dynamic color
- **THEN** custom components follow the new values

### Requirement: REQ-UIX-M3C-STABLE — Stable APIs only

The component layer MUST NOT opt in to experimental Material 3 Expressive APIs.

#### Scenario: Stable APIs only

- **WHEN** the app compiles
- **THEN** no ExperimentalMaterial3ExpressiveApi opt-in is present
