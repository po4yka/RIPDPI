# Change: Commit profile data atomically with Room

Task ID: `DAT-1793949941293491`

## Why

Profile metadata, credentials, groups, selected providers and settings use separate
stores. The application therefore maintains an encrypted write-ahead journal,
replay, compensating writes and snapshot retries. Readers can see a partial write,
and recovery has to reconstruct consistency. Room already ships in the app and
can provide atomic commit, rollback and consistent reads through SQLite.

## What Changes

- Save, delete, select and restore related profile data in one database transaction.
- Publish profile changes only after commit. Failed writes keep the previous data.
- Read private backups from one consistent database snapshot.
- Migrate existing data once, including any pending legacy mutation.
- Keep secrets encrypted and preserve runtime Stop precedence and direct boot.
- BREAKING: internal storage APIs become transaction-aware; old persistence writes
  and journal APIs are removed after migration. There is no dual-write path.

## Capabilities

### New Capabilities

- `atomic-profile-persistence`: atomic profile storage and one-time migration.

### Modified Capabilities

- None. Existing profile formats and runtime admission rules remain authoritative.

## Impact

- Kotlin `:core:data`, `:core:data:runtime-state`, `:core:data:settings`, their
  consumers, dependency injection and persistence tests.
- Room schema, encrypted storage, backup/reset and activation publication.
- No new production dependency and no Rust or wire-format change.
