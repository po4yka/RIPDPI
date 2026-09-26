## Purpose

Keep relay health evidence useful within one diagnostics archive without disclosing identifiers that join it to another export.

## ADDED Requirements

### Requirement: REQ-RELAY-ARCHIVE-UNLINKABLE — Relay identifiers stay local to one archive

The implementation MUST omit stable relay profile tokens, attempt identifiers containing profile tokens, and event identifiers derived from them from exported native event records. Relay health JSONL records MUST use identifiers generated for one archive only.

#### Scenario: Same profile in separate archives

- **GIVEN** two diagnostics archives contain relay health decisions for one saved profile
- **WHEN** a recipient compares the native event and relay health records
- **THEN** neither archive contains the stable profile token or token-derived identifiers, and the exported aliases do not match across archives

#### Scenario: Multiple decisions in one archive

- **GIVEN** one archive contains multiple relay health decisions for one profile
- **WHEN** the relay health JSONL records are read
- **THEN** the records use the same archive-local profile alias and distinct archive-local attempt aliases

### Requirement: REQ-RELAY-ARCHIVE-UNKNOWN — Missing identifiers remain unavailable

The implementation MUST export `unavailable` for missing or invalid relay health identifiers and MUST preserve the existing archive field names and types.

#### Scenario: Incomplete relay decision

- **GIVEN** a relay health decision has no valid profile or attempt identifier
- **WHEN** a diagnostics archive is created
- **THEN** its JSONL record contains `unavailable` for those identifiers and remains parseable by existing readers
