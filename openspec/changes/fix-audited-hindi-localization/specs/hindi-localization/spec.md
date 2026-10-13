## Purpose

Hindi users can understand audited privacy warnings, state labels, settings options, diagnostic results, and catalog dates without changes to network behavior.

## ADDED Requirements

### Requirement: REQ-HI-MEANING — Correct audited Hindi meanings

The app MUST correct the 63 active and seven unused confirmed resource findings and preserve the original privacy boundaries and actual action names.

#### Scenario: Review a sensitive export or failed recovery

- **WHEN** Hindi is selected and the user shares a redacted backup or reviews failed pause recovery
- **THEN** the text states that sensitive data was removed and identifies the actual recovery action
- **AND** unchecked network state is distinct from invalid state.

### Requirement: REQ-HI-RESULTS — Localize settings and known results

The app MUST localize the 46 descriptive array labels, two settings literals, and 21 known diagnostic and host-pack texts. It MUST preserve exact wire values, array order, technical names, raw evidence, and unknown catalog descriptions.

#### Scenario: Select a setting or inspect diagnostic results

- **WHEN** Hindi is selected and settings, DPI failures, PT checks, TCP16 results, or built-in host packs are shown
- **THEN** descriptive labels use Hindi resources
- **AND** selecting each option preserves its original stored value.

### Requirement: REQ-HI-DATES — Read the current app locale

The app MUST format host-pack and strategy-pack dates in the selected app language on each presentation and after a language change.

#### Scenario: Change language while the system uses English

- **WHEN** the user views catalog metadata in English, then selects Hindi, then English again
- **THEN** visible month labels follow each selected app language
- **AND** invalid host-pack timestamps keep the existing safe fallback.

### Requirement: REQ-HI-PARITY — Preserve resource contracts

The change MUST preserve all ten locales, resource types, format arguments, native language names, and exact settings wire values. Array display references MUST resolve to translated strings in every locale.

#### Scenario: Add a presentation key

- **WHEN** a new display string or plural is added
- **THEN** all ten locales contain it in the same commit
- **AND** resource, lint, and regression gates pass without baseline changes.
