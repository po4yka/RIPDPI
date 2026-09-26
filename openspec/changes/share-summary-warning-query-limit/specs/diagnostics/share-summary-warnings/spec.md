# Share summary warning selection

## ADDED Requirements

### Requirement: REQ-SHARE-WARNINGS-FILTER-BEFORE-LIMIT

The implementation MUST filter native events to warning and error levels before applying the share summary event limit. It MUST preserve selected-session scope, descending event time, and case-insensitive level matching.

#### Scenario: Selected session has newer information events

- **GIVEN** a selected session has a warning followed by 50 newer information events
- **WHEN** the user builds its share summary
- **THEN** the summary includes the warning
- **AND** it does not include events from another session

#### Scenario: Live summary has newer information events

- **GIVEN** the native event history has a warning followed by 50 newer information events
- **WHEN** the user builds a live share summary without a selected scan session
- **THEN** the summary includes the warning

#### Scenario: More than 50 matching events

- **GIVEN** there are more than 50 warning and error events
- **WHEN** the summary selects native events
- **THEN** it uses the newest 50 matching events in descending event time
