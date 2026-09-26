## Purpose

Let users choose a specific app language or return to the device language from
the same language picker in settings.

## ADDED Requirements

### Requirement: REQ-APP-LANGUAGE-SYSTEM — Restore device language

The app MUST offer a localized device-language option in the language picker.
Selecting it MUST clear the app language override.

#### Scenario: Restore after an explicit selection

- **GIVEN** the user selected a specific app language
- **WHEN** the user selects the device-language option
- **THEN** the app follows the device language and that option is selected when the picker is reopened

### Requirement: REQ-APP-LANGUAGE-EXPLICIT — Preserve explicit selection

The app MUST continue to offer each supported language as an explicit choice.
Selecting one MUST apply that language as an app override.

#### Scenario: Select an explicit language

- **GIVEN** the app follows the device language
- **WHEN** the user selects a supported language
- **THEN** that language becomes the app override and its option is selected when the picker is reopened

### Requirement: REQ-APP-LANGUAGE-LOCALES — Localize device choice

The device-language option MUST have a translated label in every shipped app locale.

#### Scenario: Open picker in a shipped locale

- **GIVEN** the app displays one of its ten shipped locales
- **WHEN** the user opens the language picker
- **THEN** the device-language option has a label in that locale
