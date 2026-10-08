## Purpose

Make confirmed subscription imports ready for use through the shared refresh path while preserving consent and single-use bootstrap semantics.

## ADDED Requirements

### Requirement: REQ-SUB-INITIAL-FETCH - Fetch before success

Ordinary subscription confirmation MUST fetch through the refresh coordinator after explicit confirmation and MUST report success only after that path persists valid profiles. AWG-only content MUST count as successful content even when the relay member count is zero.

#### Scenario: Ordinary import

- **WHEN** the user confirms a long-lived subscription
- **THEN** the client fetches once and saves its profiles before publishing success

#### Scenario: No confirmation

- **WHEN** a deep link only seeds the confirmation screen
- **THEN** the client sends no request

### Requirement: REQ-SUB-INITIAL-FAILURE - Preserve failure and retry semantics

Fetch failures and empty invalid content MUST retain the screen with an error and MUST NOT emit success. Retrying MUST reuse the same subscription group and preserve existing members on failure. Cancellation MUST propagate without a success or failure event.

#### Scenario: Rejected import and retry

- **WHEN** the server first rejects the import and later returns valid content
- **THEN** the first attempt reports failure and the second updates the same group successfully

### Requirement: REQ-SUB-INITIAL-BOOTSTRAP - Preserve single-use delivery

The importer MUST NOT invoke ordinary refresh for an already consumed bootstrap, even if a repeated link omits the bootstrap flag.

#### Scenario: Consumed bootstrap imported again

- **WHEN** a previously consumed bootstrap URL is confirmed again
- **THEN** existing profiles remain and the URL is not fetched again
