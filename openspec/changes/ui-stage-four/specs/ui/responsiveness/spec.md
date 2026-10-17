## Purpose

Keep local log and catalog presentation responsive without changing the meaning or order of user data.

## ADDED Requirements

### Requirement: REQ-UI4-LOGS — Relevant background log projection

The UI MUST preserve event order, deduplication, active-session flags, filters and clear behavior. Repeated counters or progress with unchanged event inputs MUST NOT remap events. Heavy projection MUST run off Main.

#### Scenario: Counters change without new events

- **WHEN** telemetry counters or scan progress change with unchanged events and session identity
- **THEN** log entries remain equal and event mapping does not repeat

#### Scenario: New events and session completion

- **WHEN** new events arrive or an active session ends
- **THEN** the ordered log updates with correct active flags and preserves the existing limit and filter behavior

### Requirement: REQ-UI4-CATALOG — Reuse unchanged catalog projection

The catalog MUST retain query matching, favorites, recent and applied ordering. Unchanged profile and search inputs MUST reuse their projection during unrelated UI updates.

#### Scenario: Unrelated header state changes

- **WHEN** check controls or disclosure state change without changing profiles or search
- **THEN** visible rows and order stay equal without another full catalog projection

#### Scenario: Query or profile changes

- **WHEN** a query, favorite, recent sequence or applied state changes
- **THEN** the visible catalog reflects the new input and existing selection actions remain available

### Requirement: REQ-UI4-SHELL — Narrow permission observation

The navigation shell MUST observe permission presentation without receiving unrelated telemetry-backed state. Permission changes and locale changes MUST still appear.

#### Scenario: Telemetry changes

- **WHEN** telemetry changes and permission presentation remains equal
- **THEN** the shell permission collector receives no additional value

### Requirement: REQ-UI4-EVIDENCE — Comparable local measurements

Measurement MUST record immutable source and APK identities, fixed workload sizes, warmup and samples, correct output, device and build configuration. Test drivers MUST exist only in the benchmark build. Emulator evidence MUST NOT be reported as physical-device acceptance.

#### Scenario: Before and after measurement

- **WHEN** the same workload is run before and after optimization
- **THEN** artifacts record elapsed work and frames with correctness checks and state their limitations without dropping events or weakening quality gates
