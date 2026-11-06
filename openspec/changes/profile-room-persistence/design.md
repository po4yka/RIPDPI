## Context

The portfolio task is `DAT-1793949941293491`. Room owns routing rules and AWG
metadata. Related profile data is split across preferences and DataStore. The
coordinator implements its own journal and recovery. Several synchronous selection
APIs write while holding the runtime authority lock, and several stores publish
flows before the complete operation has committed.

## Goals / Non-Goals

- Goal: one atomic SQLite commit for each profile operation and private backup.
- Goal: keep secret protection, existing profile formats and runtime authority.
- Non-goal: move diagnostics history, network caches or direct-boot runtime authority.
- Non-goal: add a backend, a database library or a second current persistence path.

## Decisions

- Extend `RipDpiDatabase` to version 3. Keep existing routing and AWG tables. Store
  the remaining serialized profile records in a Room table keyed by namespace
  and record ID. Preserve the existing domain serializers and validators.
- Encrypt each record with Android Keystore AES-256-GCM. Authenticate namespace
  and ID as associated data. Encrypt the full settings protobuf because it also
  contains a LAN authentication token. Do not store plaintext in database columns.
- Use synchronous internal DAO calls inside a short `runInTransaction` block on
  an IO dispatcher. Public persistence operations suspend. Transaction bodies do
  not suspend, invoke native code or perform network work. One session owns typed
  record access so normal stores and coordinated mutations share their codecs.
- Keep database ownership in `:core:data`. Lower modules retain domain contracts;
  they do not depend on Room or on the higher module. Update synchronous storage
  consumers to suspend rather than using blocking coroutine bridges.
- Use Room query flows for committed persistent state. Remove cache publication
  from intermediate writes. Runtime receipts remain transient and are published
  only after commit and a current-authority check.
- Preserve the separately durable Stop/reservation barrier. A failed database
  mutation may leave the runtime stopped, but cannot claim successful activation.
  A short checked selection commit may hold the existing runtime linearizer on
  the same IO thread; no suspending call runs while that lock is held.
- Move canonical AWG selection into the database. Device-protected boot state
  remains a non-secret launch hint and is validated against canonical data after
  unlock. It is not a second profile store.
- Import legacy data during explicit recovery or the first public read. Observers
  wait for successful recovery and settings validation, including after a failed
  attempt; an explicit retry or reset releases the same subscriptions. Prepare the
  legacy snapshot off the database thread, then import records, resolve pending
  legacy intent, and write the completion marker in one database transaction.
  Legacy readers are migration-only; current writes use Room exclusively.
- Remove journal preparation, replay-on-each-read, compensation for persistence
  failures and backup snapshot retry loops. Retain domain compensation for a
  failed runtime activation only where it represents a separate user operation.

## Contracts and ownership

- The primary writer owns transaction orchestration, migration, DI, settings,
  selection APIs, callers and the portfolio/OpenSpec records in this worktree.
- An isolated foundation writer owns `RipDpiDatabase*`, new `persistence/ProfileRecord*`
  DAO/entity/cipher files, the Room schema exports and their focused tests. The
  primary writer integrates this branch before running combined gates.
- `:core:data:runtime-state` owns profile types; `:core:data:settings` owns settings
  contracts. `:core:data` supplies Room implementations. Rust and protobuf field
  numbers do not change. No golden fixtures or baselines are changed.

## Risks / Trade-offs

- Synchronous selection callers must become suspending callers. Update all call
  sites and test newer Stop and stale-receipt paths; do not block the main thread.
- Database and Keystore failures must propagate. Never treat unreadable ciphertext
  as an empty profile or silently mark migration complete.
- Import must handle a pending old journal without exposing its partial state.
  Keep the source until the import commit succeeds; the marker prevents old data
  from reappearing after reset.
- Encryption adds work inside a transaction. Keep transactions limited to local
  data, and prepare large external import parsing before acquiring database locks.
- Existing failure tests assert old partial-write/replay behavior. Replace those
  expectations with independently checked complete old/new database states.

## Migration Plan

1. Add the Room table and additive version 2 to 3 migration; generate schema output.
2. Add record access, encrypted stores and the one-time legacy importer. Import
   AppSettings, profiles, credentials, groups, selection and pending old intent.
3. Move mutation/backup/reset operations to transactions and remove current legacy
   writes. Change consumers and publish committed flows.
4. Verify real Room rollback, cancellation, commit/reopen, migration idempotence,
   encrypted storage and Stop races. Run affected module tests and app tests.
5. Run `just ci-gates`, static analysis and an affected application build through
   `build-gate`. Review the combined diff independently, commit, rebase onto current
   main, integrate, push and observe hosted CI.

There is no dual-write rollback to the old storage format. A failed migration
leaves the source unchanged and retries on the next start. A successful migration
uses the new database as its sole authority.
