# persian-localization-presentation Specification

## Purpose
Provide accurate Persian UI text and localized generated diagnostics without changing raw evidence or operational behavior.

## Requirements

### Requirement: REQ-FA-COPY — Preserve audited Persian meaning

The app MUST correct all 121 confirmed Persian resource entries. Security and privacy warnings, credential disclosure, exclusion and restart actions, diagnostic operations and stored catalog states MUST preserve the English meaning and format arguments.

#### Scenario: Review sensitive support changes

- **WHEN** the selected app language is Persian and a support link changes sensitive settings
- **THEN** the title and warning explain the exposure and trust decision in Persian before apply

#### Scenario: Explain an exclusion or restart

- **WHEN** the app displays a confirmed exclusion or restart instruction
- **THEN** the Persian text describes bypassing VPN routing or restarting the running service, without describing app deletion or configuration reset

### Requirement: REQ-FA-STRATEGY — Localize generated Approach and Strategy presentation

The app MUST localize generated Approach metric names and Strategy suite, family, outcome, matrix, candidate, emitter and recommendation display text in the selected language. Raw protocol keys MUST retain grouping semantics after label localization.

#### Scenario: Display a Persian strategy report

- **WHEN** the app maps an existing report under Persian
- **THEN** generated labels and explanations use Persian resources while counts, identifiers and protocol groups keep their values

#### Scenario: Preserve an unknown token

- **WHEN** the report contains an unknown or custom suite, family, label or value
- **THEN** presentation preserves it without altering stored data or exports

### Requirement: REQ-FA-HOME — Localize normal Home analysis presentation

The app MUST localize generated Home outcome headlines and summaries, warnings, confidence and coverage, recommendations, applied display settings and stage labels and summaries. Confidence colors MUST retain the same semantic grade after translation.

#### Scenario: Display a completed analysis

- **WHEN** a full or quick Home analysis produces a normal outcome under Persian
- **THEN** visible generated output uses the selected language, including stage progress and result cards

#### Scenario: Preserve raw evidence

- **WHEN** the same raw Home outcome is projected under English, Persian and English again
- **THEN** presentation follows each selected language while raw records, identifiers, addresses, user names and unknown text remain unchanged

### Requirement: REQ-FA-PARITY — Keep all locale and verification contracts

Every new resource key MUST exist with matching type and format arguments in all ten locales in the same source commit. Regression checks, affected app tests, lint, static analysis and screenshot comparison MUST pass without unauthorized golden or quality baseline updates.

#### Scenario: Publish a validated change

- **WHEN** the change is integrated into main and pushed
- **THEN** local evidence and successful hosted CI identify the exact published source, with native-speaker and physical-device limits stated separately
