## Context

`MainActivity` sanitizes its Intent before creating the NavHost. Pending requests live in an Activity-local `MainActivityShellController`, which is recreated from that sanitized Intent after rotation. A pending relock signal is also stored only in the shell.

## Goals / Non-Goals

- Goal: restore deferred shell requests before Compose creates or restores navigation state.
- Non-goal: retain a process-only import token after process death.

## Decisions

- Save pending shell requests in `onSaveInstanceState` and pass them into the replacement shell in `onCreate` before the Intent is sanitized.
- Restore only supported import route types. Drop import tokens no longer present in the process store.
- Preserve the existing app lock gate and Intent sanitization.

## Contracts and ownership

- Only Android `:app` Activity shell and tests change. No Rust crate, public API, persistent storage, or serialized shared file changes.
- Android saved instance state is transient. There is no schema migration.

## Risks / Trade-offs

- A large support-settings import payload enters saved instance state. The inbound parser already limits accepted input; verification includes app tests and source checks.
- Device recreation timing is not covered by local unit tests; the local test exercises state round-trip and pending dispatch state.

## Migration Plan

No migration. Rollback restores previous shell initialization. Verify with `MainActivityShellControllerTest`, full `:app:testGithubFullDebugUnitTest`, detekt, and app ktlint checks.
