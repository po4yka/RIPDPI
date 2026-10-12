## Purpose

Present app instructions and diagnostic results in the selected language without changing machine contracts or user data.

## ADDED Requirements

### Requirement: REQ-L10N-MEANING — Preserve source meaning

The app MUST translate all audited Russian instructions and warnings without adding unsupported promises or removing actions and safety information.

#### Scenario: Review sensitive changes

- **WHEN** a Russian user reviews a support settings link or full diagnostics action
- **THEN** the UI describes sensitive changes and applicable setting changes in Russian

#### Scenario: Resolve an error

- **WHEN** an audited permission, DNS, update, editor, or pause failure is shown
- **THEN** the Russian text preserves the cause and next action stated in English

### Requirement: REQ-L10N-LOCALE — Persist and honor app language

The app MUST retain the selected app language on supported Android versions and use it for strings obtained outside Activity contexts.

#### Scenario: Restart on older Android

- **WHEN** a user selects Russian on API 27–32 and restarts the process
- **THEN** the app restores Russian independently of the system language

#### Scenario: Resolve non-Activity strings

- **WHEN** the system language is English and the app language is Russian
- **THEN** ViewModel strings and service notification text use Russian

### Requirement: REQ-L10N-COUNTS — Use grammatical counts

The app MUST use locale-specific plural rules for event summaries and active session counts.

#### Scenario: Russian plural boundaries

- **WHEN** a count is 1, 2, 5, 21, 22, or 25
- **THEN** the displayed Russian event or session count has the correct noun form and no English suffix

### Requirement: REQ-L10N-PRESENTATION — Localize known dynamic prose

The app MUST localize audited DPI suite, routing protection, and strategy presentation while preserving technical identifiers and custom values.

#### Scenario: Show diagnostic results

- **WHEN** a Russian user runs a DPI suite or opens strategy or routing details
- **THEN** known labels, statuses, explanations, and recommendations are Russian
- **AND** protocol tokens, wire values, and user-entered values remain intact

### Requirement: REQ-L10N-PARITY — Preserve locale contracts

The app MUST provide every new resource in all ten locales with compatible format arguments and required plural forms.

#### Scenario: Add a presentation resource

- **WHEN** a new localized resource is added
- **THEN** locale parity, translation export, and Android lint remain valid
