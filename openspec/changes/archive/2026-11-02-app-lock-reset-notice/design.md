## Context

`SettingsViewModelBootstrapper` wipes the PIN and turns app lock off when `PinVerifier.isKeyAvailable()` is false and a backup PIN is stored. It then posts a notice through `SettingsNoticeState`, which is an in-memory `StateFlow`. A restart loses the notice. The user sees app lock off with no explanation.

## Goals / Non-Goals

- Goal: the notice outlives the process and shows until the user acts.
- Goal: the new field follows the additive `AppSettings` rules.
- Non-goal: change how the PIN is stored or verified.
- Non-goal: a general persisted notice system. One flag covers this case.

## Decisions

- Add one `bool` field to `AppSettings`. Find the number with the commands in the `protobuf-schema-evolution` skill and take the next one above the highest declared number, outside every reserved range. Do not trust a number written in this document.
- The proto3 default (false) is inert, so `AppSettingsSerializer` needs no special default, but it records the explicit default for the field as the skill requires.
- The bootstrapper writes the flag in the same DataStore update that clears the PIN and the app lock setting. One update keeps the state consistent after a crash.
- The Settings list reads the flag. `SettingsNoticeState` stays for transient notices and does not own this one.
- Acknowledging the notice and setting a new PIN both clear the flag. The clear points are `setBackupPin` with a non-blank PIN and `acknowledgeAppLockResetNotice`; the in-memory `clearWhenPinSet` logic is removed.
- Backup and restore: the flag is excluded from backups. A restored backup carries its own PIN state, and a reset says "this device lost its key", which is a local fact. Confirm this against the backup path before implementation. If the backup path copies every field by default, add the field to its exclusion list the way `proxy_lan_auth_token` is excluded.
- `CONFIG_CONTRACTS.md` records the field name, number, meaning, and the backup decision.

## Risks / Trade-offs

- A wrong field number silently corrupts stored settings. → Check the live schema, then add the migration test.
- A stale flag could show a notice with no cause. → Both clear paths are tested, and a new PIN clears the flag.
- Backup handling is an open decision. → Resolve it first and record it in this file before code.

## Migration Plan

Additive proto field with an inert default, so no data migration is needed. The migration test decodes bytes written without the field. Implement in an isolated worktree, run the data and app unit tests, and archive only through `taskctl` after review.
