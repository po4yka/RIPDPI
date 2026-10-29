# french-localization-presentation Specification

## Purpose
Present audited French text and diagnostic states with clear meanings while preserving technical data and locale parity.

## Requirements

### Requirement: REQ-FR-COPY — Correct the confirmed French resource set

The app MUST correct all 138 confirmed resource keys, preserve consent, retention and recovery meanings, and reference the visible French controls.

#### Scenario: Sensitive support changes

- **WHEN** the selected locale is French and a sensitive support link is shown
- **THEN** the warning explains traffic, credentials, diagnostics and privileged features in French and requires trust before application.

#### Scenario: Unavailable resume clock

- **WHEN** the pause clock or boot identity changes
- **THEN** the French recovery instruction names the visible Reprendre maintenant control.

### Requirement: REQ-FR-DISPLAY — Localize diagnostic display values

The app MUST map known connection states, its generated unknown-network token and known RTT bands to readable labels in the selected app locale. Unknown custom values and raw storage, wire and export fields MUST remain exact.

#### Scenario: Retained stopped telemetry

- **WHEN** stopped telemetry is retained and the app changes from English to French and back
- **THEN** Live state badges and headlines follow the selected language without changing the sample.

#### Scenario: Missing network and RTT boundaries

- **WHEN** current telemetry has no network session or has a known RTT band
- **THEN** the ordinary UI shows a localized unknown-network label or a range in milliseconds rather than an internal code.

### Requirement: REQ-FR-DEBUG — Localize debug performance prose

The debug performance card MUST use localized prose and selected-locale decimal formatting for durations. Technical stage identifiers MUST remain exact.

#### Scenario: French fractional duration

- **WHEN** a French debug card displays a fractional millisecond duration
- **THEN** the duration uses the French decimal separator and its surrounding prose is translated.

### Requirement: REQ-FR-PARITY — Preserve all locale contracts

New app resource keys MUST exist in all ten locales in the same commit. Existing native language names, protocol names, format arguments and plural contracts MUST remain valid.

#### Scenario: New RTT or debug caption

- **WHEN** a display resource is added
- **THEN** every shipped locale has the same resource type and compatible arguments, and app/service lint passes.
