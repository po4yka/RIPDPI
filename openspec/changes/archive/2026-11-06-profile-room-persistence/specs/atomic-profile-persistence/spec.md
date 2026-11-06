## Purpose

Keep related profile data consistent across failures, process restart, backup
restore and concurrent activation while retaining local secret protection.

## ADDED Requirements

### Requirement: REQ-PERSIST-ATOMIC — Atomic profile mutations

The application MUST commit metadata, secrets, group membership, saved selection
and profile-derived settings in one Room transaction. Failure or cancellation before
commit MUST leave the complete previous persistent state. Readers MUST NOT observe partial
commits, and persistence MUST NOT require an application write-ahead journal.

#### Scenario: A write fails after its first record

- **WHEN** a profile mutation fails after writing metadata but before all related
  records have been written
- **THEN** the previous metadata, secrets, groups, selection and settings remain
  visible both immediately and after reopening the database.

#### Scenario: A complete mutation succeeds

- **WHEN** all writes in a profile mutation commit
- **THEN** readers and observers receive the complete new state.

#### Scenario: Cancellation arrives after commit

- **WHEN** the caller is cancelled after SQLite commits the transaction
- **THEN** the complete new state remains saved, and receipt publication that must
  precede the next authority transition has already finished under the same lock.

### Requirement: REQ-PERSIST-BACKUP — Transactional backup and reset

The application MUST read a private backup from one database snapshot and replace
or clear related profile state in one transaction. It MUST NOT use snapshot retry
loops or captured rollback after-images to simulate atomic persistence.

#### Scenario: Restore fails

- **WHEN** a restore fails before database commit
- **THEN** the complete previous profile state remains available.

#### Scenario: A reset has committed

- **WHEN** the app restarts after a successful reset
- **THEN** obsolete preferences cannot restore deleted profiles or credentials.

### Requirement: REQ-PERSIST-SECURITY — Secret protection and runtime authority

The application MUST store secret-bearing values as Android Keystore encrypted
data, including settings tokens and group subscription credentials. A failed
mutation MUST NOT publish a successful activation capability. A newer Stop command
MUST supersede an older activation. Non-secret direct-boot state MUST remain
available before credential unlock.

#### Scenario: Stop races with a suspended write

- **WHEN** Stop arrives while a profile activation write is suspended
- **THEN** the older activation cannot start or resume a connection.

#### Scenario: Inspect database storage

- **WHEN** profiles with credentials and settings tokens are committed
- **THEN** those secrets do not occur as plaintext in the database or its WAL.

### Requirement: REQ-PERSIST-MIGRATION — One-time legacy import

The application MUST import existing profile stores and pending legacy mutation
intent before exposing migrated data. Import and its completion marker MUST commit
together. An unreadable source MUST fail closed without marking import complete
or deleting the source. A completed import MUST never run again after reset.

#### Scenario: Import is interrupted

- **WHEN** import fails before commit
- **THEN** legacy data remains intact and the next attempt can retry the import.

#### Scenario: A legacy journal contains a partial mutation

- **WHEN** the app imports stores with a pending mutation
- **THEN** it exposes one consistent final state and does not expose the partial
  legacy state as a completed migration.
