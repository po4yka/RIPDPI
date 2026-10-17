## Context

The shared Simple catalog has one tall control item before rows. History models
contain filtered lists, so empty cards cannot distinguish absence from a query.
This change is confined to the app module and presentation projection.

## Goals / Non-Goals

- Goal: make profiles and correct history actions visible with less chrome.
- Goal: preserve operation effects, failure recovery and accessibility.
- Non-goal: service, database, schema, probe target or dependency changes.
- Non-goal: unrelated Advanced IME acceptance, exact Undo or performance rewrites.

## Decisions

- Keep profile search/filters prominent. The existing explanation of HTTP checks
  and successful selection stays visible. Disclose URL and bulk check controls.
- Keep the applied indicator, selection semantics and measurement/failure text
  visible in every row. Keep check-and-select primary; disclose check-only and
  timestamps. A checking row exposes Cancel without requiring disclosure.
- Preserve all sections and filters. Several profile kinds can be applied;
  do not invent a single global selected profile. Any selected-first grouping
  must preserve Recent ordering and must not reintroduce filtered-out profiles.
- Keep cleanup/failure recovery outside the collapsed check options.
- Add source-presence booleans to HistoryUiState, computed from mapped source
  lists before filtering. Defaults derive from visible lists for existing callers.
- Hide search/filter cards for an empty source. Keep them for existing sources,
  including zero filtered rows. Give zero results an explicit Clear filters action.
- Thread navigation callbacks through HistoryRoute/Screen. Root maps Connection
  to Route.Config and Diagnose to Route.Diagnostics() with autoStartScan=false.
- Explicit history navigation MUST NOT restore saved destination stacks or
  arguments: an old auto-start flag or child screen can override this action.
  Keep launchSingleTop and test real saved-back-stack behavior.
- Use local history empty-card composition instead of broad shared API changes.
- Use RDS empty-state, history, accordion and profile-switcher references. The
  utility has no exact screen reference; retain its existing sections/contracts.

## Contracts and ownership

- Only :app UI and HistoryUiState projection change; no Rust crate changes.
- Ownership is recorded in docs/tasks/issues/compact-catalog-history.md before
  parallel writers start. Root alone owns all locale files, shared test tags,
  navigation host, translation exports, task records and integration.
- No dependency, JNI, protobuf, stored data or public network contract changes.
- Existing golden PNG remain fixed until explicit family-scoped user approval.

## Risks / Trade-offs

- Disclosure can hide critical state: keep measurements, failures and recovery visible.
- Query-empty can resemble first use: derive source presence before filtering.
- Large text can increase height: wrap full labels and keep one scrolling surface.
- RTL can reorder controls: use existing tokens, logical alignment and actual checks.
- UI-only tests cannot prove navigation: validate real routes on a leased emulator.

## Migration Plan

No migration is needed. Rollback reverts presentation/callback changes. Validate
behavior tests, locale parity/lint, theme/static checks, affected screenshots and
real Full/Simple APKs in light/dark, compact, maximum-font and RTL states. Review,
rebase and rerun combined checks before authorized ff-only integration and push.
