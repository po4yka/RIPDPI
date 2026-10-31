## Purpose

Rearrange the main screen families to Material 3 canonical layouts and action placement so each window size class gets a layout made for it.

## ADDED Requirements

### Requirement: REQ-UIX-M3L-LISTDETAIL — List-detail screens

Diagnostics, History, Logs, and Settings MUST show list and detail panes together on expanded and wider windows and MUST show one pane at a time on compact windows. Back MUST return from detail to list on compact windows.

#### Scenario: List-detail screens

- **WHEN** a user opens an item on an expanded window
- **THEN** the detail appears beside the list and the list keeps its scroll position

### Requirement: REQ-UIX-M3L-SUPPORTING — Supporting-pane screens

Config editors and Home MUST use a supporting pane on expanded and wider windows. The Home connection actuator MUST stay the primary control.

#### Scenario: Supporting-pane screens

- **WHEN** a user opens a config editor on an expanded window
- **THEN** the editor and its supporting help or preview are visible together

### Requirement: REQ-UIX-M3L-ACTIONS — Material action placement

Each screen's primary action MUST use a FAB, extended FAB, top app bar action, or floating toolbar. Destructive actions MUST keep confirmation.

#### Scenario: Material action placement

- **WHEN** a user looks for the main action on a screen
- **THEN** it is in a Material 3 action placement with a visible label or content description

### Requirement: REQ-UIX-M3L-SEARCH — Search in long lists

Logs and History MUST offer a search field that filters the visible list. Material 3 SearchBar replaces it once it is available without an experimental opt-in.

#### Scenario: Search in long lists

- **WHEN** a user types a query in Logs
- **THEN** only matching rows remain and clearing the query restores the list

### Requirement: REQ-UIX-M3L-REMAINING — Remaining screen families

All other screen families MUST use the shared app bars, component facade, and width rules, with no clipped content at 200% font or in RTL.

#### Scenario: Remaining screen families

- **WHEN** each remaining screen renders at 200% font in Arabic
- **THEN** no content clips and all actions stay reachable
