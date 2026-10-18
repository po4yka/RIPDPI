## Purpose

Keep the established RDS appearance and behavior while allowing reusable CommonStyle visual overrides.

## ADDED Requirements

### Requirement: REQ-RDS-STYLE-PARITY — Preserve default rendering

The implementation MUST preserve default component geometry, colors, content, and motion when no override is supplied.

#### Scenario: Existing component call

- **WHEN** a Full or Simple screen calls a migrated component without a visual override
- **THEN** the component retains its established screenshot and semantics contract.

### Requirement: REQ-RDS-STYLE-OVERRIDE — Apply optional visual overrides

The implementation MUST apply base, variant, and caller styles in that order while preserving required touch targets and behavior.

#### Scenario: Override a container color

- **WHEN** a caller supplies a container property already set by the variant
- **THEN** the caller value is rendered and the existing action and test selector remain available.

#### Scenario: Disabled action

- **WHEN** a disabled or loading control receives a visual override and click input
- **THEN** the action is not invoked and the disabled or loading semantics remain available.

### Requirement: REQ-RDS-STYLE-THEME — Resolve current tokens and state

The implementation MUST use current theme, contrast, interaction, and motion values without capturing stale values.

#### Scenario: Theme and motion change

- **WHEN** theme, contrast, or motion preferences change with a component displayed
- **THEN** the component updates and reduced or disabled motion follows existing RDS policy.

#### Scenario: Cancel a press

- **WHEN** a press is cancelled or focus changes
- **THEN** the visual state returns to the correct current state without invoking the action.

### Requirement: REQ-RDS-STYLE-COMPATIBILITY — Gate the experimental stack

The implementation MUST verify dependency compatibility before component migration and MUST keep native and persisted contracts unchanged.

#### Scenario: Dependency gate fails

- **WHEN** the published beta stack cannot compile with the selected SDK and build tools
- **THEN** dependent migration stops and the exact blocker is recorded without weakening a gate.

### Requirement: REQ-RDS-STYLE-ACCEPTANCE — Separate verification levels

The implementation MUST report local, screenshot, device, and performance evidence separately and MUST NOT send user data to an external service.

#### Scenario: Performance acceptance

- **WHEN** comparable original, dependency-only, and styled profileable runs are available
- **THEN** repeated regressions above five percent in median startup or p95 CPU frame duration prevent acceptance.
