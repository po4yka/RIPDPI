## Purpose

Make recovery, persona selection, history restrictions, and new event arrivals clear without changing runtime or persistence contracts.

## ADDED Requirements

### Requirement: REQ-UIC-HOME - Direct recovery action

The Home advisory card MUST expose its first available recovery action in the existing priority order. It MUST preserve the highest-priority warning and disclose only the remaining items. A recovery action MUST invoke its existing callback once.

#### Scenario: Several advisories include an actionless priority warning

- **WHEN** the highest-priority warning has no action and another warning has a recovery action
- **THEN** both the priority warning and the first available recovery are visible without expansion, and the remaining count excludes both

#### Scenario: Recovery is selected

- **WHEN** the user selects the visible recovery action
- **THEN** its callback runs once without a separate disclosure tap

### Requirement: REQ-UIC-PERSONA - Explicit interface choice

The settings persona row MUST open a choice with Guided and Advanced descriptions and selected semantics. Opening, Back, or cancellation MUST preserve the saved value. An explicit different choice MUST use the existing save callback once.

#### Scenario: Choice is cancelled

- **WHEN** the user opens the persona choice and dismisses it without selecting another mode
- **THEN** the saved mode and its UI remain unchanged

#### Scenario: Another mode is selected

- **WHEN** the user selects the other radio option
- **THEN** exactly that stored persona key is saved once and the choice closes

### Requirement: REQ-UIC-FILTERS - Compact and reversible filters

History MUST keep search, the active restriction count, and removable active filters available while the full controls are collapsed. Search and categorical restrictions MUST contribute to the count; automatic following MUST NOT. Removing one restriction MUST preserve the others. Existing clear-all and empty-result behavior MUST remain available.

#### Scenario: Full controls are opened and closed

- **WHEN** the user changes a filter and collapses its controls or switches history sections
- **THEN** the applied values remain selected and the compact summary reflects them

#### Scenario: One restriction is removed

- **WHEN** the user removes an active category or search chip
- **THEN** only that restriction is cleared and the results update using the existing callbacks

### Requirement: REQ-UIC-NEW - Truthful new-event count

The return-to-latest action MUST show the count of matching newly newest event identities received while the reader is away from the live edge. Initial load, old backfill, repeated identities, group refreshes, and content-filter changes MUST NOT create a new-event count. Same-timestamp distinct new identities MUST remain countable. Reaching the live edge or completing the explicit jump MUST clear the count without changing filters or automatic-following preference.

#### Scenario: Matching events arrive while the reader is away

- **WHEN** new matching event identities arrive after the initial baseline
- **THEN** the count increases while the same old event stays at the same reader position

#### Scenario: Filters or old history change

- **WHEN** the content filters change, a group is refreshed, or older events are loaded
- **THEN** the counter does not present these records as new arrivals

### Requirement: REQ-UIC-ACCESS - Accessible and bounded presentation

The new controls MUST support all ten locales, light and dark themes, font scale 2.0, RTL, and disabled motion. Descriptions and action labels MUST wrap. The changes MUST use existing callbacks, app-only projections, and theme tokens without a new dependency or network, storage, JNI, or protobuf contract.

#### Scenario: Large-font RTL operation

- **WHEN** the user opens the persona choice, advisory details, or history filters at font scale 2.0 in RTL
- **THEN** labels and selected state remain readable and all primary actions remain reachable
