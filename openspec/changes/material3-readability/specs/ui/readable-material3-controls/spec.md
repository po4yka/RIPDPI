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

### Requirement: REQ-UI-SHARED-ACTIONS — Readable shared actions and feedback

Shared standalone actions MUST use 24dp glyphs in normal and compact density and preserve a 48dp hit area. Decorative leading symbols in full-size feedback and cards MUST use 32dp inside a 40dp circle. Action labels MUST remain readable at 2x font scale without font reduction or unintended ellipsis. Small inline telemetry markers MAY retain compact roles.

#### Scenario: Compact localized action

- **WHEN** a long action is shown at 320dp width and 2x font scale
- **THEN** its full label and action glyph remain visible and the callback remains available

#### Scenario: Feedback and navigation

- **WHEN** a user views feedback or bottom navigation in light, dark, or RTL mode at large font scale
- **THEN** leading symbols, dismiss controls, and destination names are readable and selectable without clipping

### Requirement: REQ-UI-ADAPTIVE-CHOICES — Adaptive choices and localized facts

Choice groups MUST use content-driven dimensions rather than fixed heights or forced equal widths that clip labels. DNS titles and descriptions MUST not compete with a large localized selected badge. Localized labels, status values, and application identifiers MUST wrap in full while preserving selection, disabled states, and callbacks.

#### Scenario: Narrow relay and DNS choices

- **WHEN** relay or DNS choices are shown at 320dp width and 2x font scale
- **THEN** labels and badges remain complete and the selected choice stays accessible

### Requirement: REQ-UI-DATA-LAYOUT — Complete data and recovery surfaces

Data-card headers and important label/value facts MUST remain readable at narrow widths and large font scale. Throughput controls and stat cells MUST render background and text as one surface. Recovery action labels MUST be complete. Import and summary grids MUST adapt where multiple columns starve meaningful text. History section navigation MUST preserve accessible selection and reachability.

#### Scenario: Throughput and recovery

- **WHEN** a user views throughput or recovery at default or 2x font scale
- **THEN** window labels remain inside their selected surfaces, stat values remain inside their panels, and both recovery actions have complete labels

#### Scenario: Narrow data screen

- **WHEN** data cards are shown at 320dp width with a large localized title
- **THEN** metadata and controls remain available without clipping the title or important values
