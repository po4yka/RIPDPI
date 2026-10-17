# Change: Compact Simple profiles and guide empty history

Task ID: `UIX-1792213600805378`

## Why

The Simple profile catalog puts setup controls before profiles and repeats two
large actions on each card. Empty history explains absence without a useful next
step. Users need to find profiles sooner and distinguish no history from no
search results.

## What Changes

- Keep search, profile state and measurement results prominent; disclose check
  options and secondary row details on request.
- Preserve all check, select, cancel, favorite and failure recovery actions.
- Give empty connection/event history a route to Connection and empty diagnostics
  a route to Diagnose. Navigation does not start a service or scan.
- Keep filtered-empty history searchable and offer Clear filters instead.
- Provide every new label in all ten locales. No breaking change.

## Capabilities

### New Capabilities

- `ui/catalog-history-clarity`: compact profile controls and useful history states.

### Modified Capabilities

- None.

## Impact

- App presentation, history projection, navigation callbacks, resources and tests.
- No dependency, native, service, persistence, wire or network contract changes.
