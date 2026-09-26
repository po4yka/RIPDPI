## Purpose

The domain bypass editor protects an active rule when a draft has invalid entries. It keeps explicit clearing and partial saves available.

## ADDED Requirements

### Requirement: REQ-INVALID-ONLY-DRAFT — Preserve the active rule

The implementation MUST leave the managed rule unchanged when a draft has errors and no valid entries. It MUST NOT report a save or clear action for that draft.

#### Scenario: Invalid-only draft

- **GIVEN** an active managed bypass rule
- **WHEN** the user saves a draft that has only invalid entries
- **THEN** the active rule and its order stay unchanged
- **AND** the editor shows the validation errors without a saved or cleared confirmation

### Requirement: REQ-EMPTY-DRAFT — Keep explicit clearing

The implementation MUST remove the managed rule when the user saves a blank draft.

#### Scenario: Blank draft

- **GIVEN** an active managed bypass rule
- **WHEN** the user saves a blank draft
- **THEN** the managed rule is removed

### Requirement: REQ-MIXED-DRAFT — Keep partial saves

The implementation MUST save valid entries from a draft that also has invalid entries. It MUST keep the validation errors visible.

#### Scenario: Mixed draft

- **WHEN** the user saves a draft with one valid entry and one invalid entry
- **THEN** the managed rule contains the valid entry
- **AND** the editor shows the error for the invalid entry

### Requirement: REQ-PENDING-DRAFT — Wait for the saved rule

The implementation MUST NOT save the initial empty draft before the managed rule has loaded.

#### Scenario: Save before hydration

- **GIVEN** a saved bypass rule and a delayed repository emission
- **WHEN** Save is requested before the editor draft is initialized
- **THEN** the saved rule stays unchanged
- **AND** the editor does not report a clear action
