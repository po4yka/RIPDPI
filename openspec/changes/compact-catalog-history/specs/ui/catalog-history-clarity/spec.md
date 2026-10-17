## Purpose

Make profile choice and empty history understandable without hiding action effects or failure recovery.

## ADDED Requirements

### Requirement: REQ-CH-CATALOG — Compact profile choice

The catalog MUST prioritize search and profile rows over optional probe setup.
Selected state and measurement results MUST remain visible. Existing check-only,
check-and-select, cancel and favorite callbacks MUST retain their behavior.

#### Scenario: A populated catalog opens

- **WHEN** a user opens profiles at 360dp width and normal font
- **THEN** the first profile is visible without opening optional check options
- **AND** URL configuration and bulk checking remain available through disclosure

#### Scenario: A check is active or recovery is needed

- **WHEN** a profile is checking, cleanup is pending or an action has failed
- **THEN** cancel, cleanup recovery and failure information remain reachable regardless of disclosure state

### Requirement: REQ-CH-HISTORY — Useful history states

History MUST distinguish an empty source collection from an empty filtered result.
Genuine empty connections/events MUST offer navigation to Connection. Genuine
empty diagnostics MUST offer navigation to Diagnose without automatically scanning.
Filtered-empty results MUST offer Clear filters without a misleading creation CTA.

#### Scenario: No history has been recorded

- **WHEN** the raw source for the selected history section is empty
- **THEN** one explanation and the corresponding navigation action are shown
- **AND** search and filters do not occupy the primary empty state

#### Scenario: A query hides existing records

- **WHEN** the source contains records but active filters produce zero rows
- **THEN** search/filter controls and Clear filters are available
- **AND** the screen does not claim no records have ever existed

### Requirement: REQ-CH-ACCESS — Adaptive accessible presentation

The affected UI MUST preserve complete labels, 48dp targets, keyboard access,
selected/expanded semantics, normal and maximum fonts, both themes and RTL.
All new or changed text MUST be present in all ten shipped locales.

#### Scenario: A narrow large-font device opens optional actions

- **WHEN** the viewport is narrow with font scale 2.0
- **THEN** disclosure, profile actions and history actions can be reached without clipped labels
- **AND** disclosure state and profile selection are available to accessibility services

### Requirement: REQ-CH-ACCEPTANCE — Observed validation and integration

The change MUST have behavior regression checks and real emulator acceptance on
recorded Full/Simple APKs. It MUST preserve service, persistence and network
contracts and pass combined-tree checks before main integration and remote push.

#### Scenario: The implementation is accepted

- **WHEN** the combined source is ready for integration
- **THEN** test and emulator evidence identifies the exact source and APK hashes
- **AND** local, device, main, push and hosted CI results are reported separately
