## Purpose

Define readable primary controls and stable navigation for the Android app at standard and large user font sizes.

## ADDED Requirements

### Requirement: REQ-UI-READABLE-TYPE — Primary text and icons

The UI MUST use 16/24sp for primary body text and 24dp for standalone action and navigation icons. Settings supporting text MUST use 14/20sp. Metadata and inline indicators MAY retain smaller roles. Text MUST use sp and respect system font scale.

#### Scenario: Read primary settings content

- **WHEN** a user views settings at default font scale
- **THEN** the title uses primary body text and the description uses supporting body text

#### Scenario: Scale primary text

- **WHEN** system font scale is 2x
- **THEN** primary text scales without shrinking text to fit fixed controls

### Requirement: REQ-UI-SETTINGS-LAYOUT — Settings row width

Settings rows MUST keep their primary label readable. A text value MUST appear below the label and supporting description. A navigation chevron or switch MUST use content width instead of half of the row. Rows MUST grow to fit text and preserve the existing action and switch semantics.

#### Scenario: Long value and label

- **WHEN** a row contains a long localized value at 2x font scale
- **THEN** its text can wrap below the label without competing with a half-width trailing block

#### Scenario: Disabled switch row

- **WHEN** a switch row is disabled
- **THEN** it remains labelled and exposes its state without invoking the callback

### Requirement: REQ-UI-DIAGNOSTICS-TABS — Diagnostics sections

Diagnostics MUST expose Dashboard, Scan, and Tools as horizontally scrollable Material 3 tabs. Selection MUST retain the existing section callback and selected semantics. At large font scale the selector MUST remain horizontal rather than create a fixed vertical stack above results.

#### Scenario: Change section

- **WHEN** the user selects another diagnostics tab
- **THEN** the section callback receives that section and the selected state remains accessible

#### Scenario: Large font and narrow screen

- **WHEN** the user opens diagnostics at 320dp width and 2x font scale
- **THEN** all sections remain reachable through horizontal navigation and content remains below one tab strip

### Requirement: REQ-UI-PRESERVE-CONTRACTS — Preserve local behavior

The change MUST preserve theme, reduced motion, locale keys, offline behavior, and network and storage contracts. Existing screenshot fixtures MUST remain unchanged unless the user authorizes blessing their family.

#### Scenario: Theme and font verification

- **WHEN** the combined implementation is checked locally
- **THEN** fresh light, dark, and large-font images are inspected and unavailable device or hosted evidence is stated
