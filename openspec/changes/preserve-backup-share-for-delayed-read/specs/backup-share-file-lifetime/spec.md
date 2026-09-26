## ADDED Requirements

### Requirement: REQ-BACKUP-SHARE-DELAYED-READ — Preserve shared URI data

After a redacted backup share starts successfully, the app MUST keep its
FileProvider URI readable after the chooser returns or the backup route closes,
until the bounded cache retention expires or Android evicts cache.

#### Scenario: Recipient reads after route closure

- **GIVEN** a redacted backup share has started
- **WHEN** the chooser returns and the backup route closes before the recipient opens the URI
- **THEN** the recipient can read the complete backup from the granted URI

#### Scenario: Share fails before launch

- **GIVEN** a temporary share file was created
- **WHEN** backup generation or chooser launch fails
- **THEN** the app deletes that temporary file immediately

### Requirement: REQ-BACKUP-SHARE-RETENTION — Bound cache lifetime

The app MUST keep distinct filenames for successful shares and MUST remove
share files older than 24 hours when the app starts, the backup screen opens,
or a new share starts, without deleting more recent files.

#### Scenario: New share while an earlier recipient is still reading

- **GIVEN** a recent successful share file remains in the backup share cache
- **WHEN** a new share starts
- **THEN** the earlier file stays readable and the new share has a distinct URI
