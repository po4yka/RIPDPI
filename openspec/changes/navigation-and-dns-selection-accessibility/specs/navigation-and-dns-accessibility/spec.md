## Purpose

Navigation and DNS selection controls remain understandable and usable when screen height or font size changes and when assistive technology is active.

## ADDED Requirements

### Requirement: REQ-RAIL-REACHABILITY — Every rail destination remains reachable

The app MUST permit users to reach every navigation rail destination on short screens at large font scale.

#### Scenario: Short landscape screen

- **WHEN** a user opens the rail at 2x font scale in a short landscape window
- **THEN** the user can scroll to and activate each destination

### Requirement: REQ-DNS-SELECTION-SEMANTICS — Selected DNS option is announced

The app MUST expose the selected state of each DNS option card to accessibility services.

#### Scenario: Selected and unselected cards

- **WHEN** a user explores DNS option cards with a screen reader
- **THEN** the selected card reports selected and the other cards report unselected
