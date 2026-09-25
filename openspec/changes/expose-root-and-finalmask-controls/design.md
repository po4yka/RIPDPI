## Context

`SettingsPreferenceSections` hides the root strategies route while `rootModeEnabled` is false. `RootModeStrategiesScreen` can show a disabled state but cannot change the setting. `RelayFields` shows Finalmask for every relay while save validation accepts only VLESS Reality with xHTTP and Cloudflare Tunnel.

## Goals / Non-Goals

- Goal: expose an explicit persisted root mode control and align Finalmask visibility with validation.
- Non-goal: change native root execution, relay protocols, or the settings schema.

## Decisions

- Keep the root strategies Settings row visible, and put the opt-in switch on the dedicated screen beside its current root warning. This reuses the existing destination and limits Settings density.
- Reuse the current `AppSettingsRepository.update` path through the screen ViewModel to persist `rootModeEnabled`.
- Use one eligibility predicate for Finalmask rendering and save validation so the UI cannot drift from the save rule.

## Contracts and ownership

- Affected module: `:app`; no Rust crate or JNI/wire contract changes.
- Existing protobuf field `root_mode_enabled` remains unchanged; no migration.
- Locale XML sets and `docs/tasks/board.md` are serialized at integration. This branch owns root-mode strings and relevant Compose/tests only.

## Risks / Trade-offs

- Root mode can be selected on an unrooted device; the existing runtime degrades gracefully. The screen keeps the warning visible.
- Existing saved unsupported Finalmask values are not altered by this UI change; save validation remains the guard.

## Migration Plan

No data migration. Forward and rollback use normal app deployment. Verify focused app tests, locale parity, lint, and architecture health before integration.
