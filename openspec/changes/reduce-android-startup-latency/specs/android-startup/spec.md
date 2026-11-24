## Purpose

Reduce the delay before the first usable Android screen and detect startup regressions.

## ADDED Requirements

### Requirement: REQ-ANDROID-STARTUP-RECOVERY — Recover and open the first screen

Startup MUST preserve required data recovery, process exit reconciliation, and the order of runtime history startup. Archive cache maintenance MUST NOT block diagnostics initialization.

#### Scenario: Cold launch with slow cache maintenance

- **WHEN** archive cache maintenance cannot complete immediately
- **THEN** startup can open the first screen after required recovery, and the application still schedules the maintenance

### Requirement: REQ-ANDROID-STARTUP-METRICS — Measure usable startup

The application MUST report full display after it draws a destination with loaded startup settings.

#### Scenario: Recovery or settings are pending

- **WHEN** the first destination depends on pending recovery or settings
- **THEN** the application waits to report full display until the destination can draw

### Requirement: REQ-ANDROID-STARTUP-REGRESSION — Compare measured startup times

The scheduled and manual benchmark MUST check cold and warm startup metrics against a measured reference from the same lane.

#### Scenario: A required measurement is missing or exceeds the regression limit

- **WHEN** the benchmark omits a required measurement or a result exceeds the configured limit
- **THEN** the regression check fails
