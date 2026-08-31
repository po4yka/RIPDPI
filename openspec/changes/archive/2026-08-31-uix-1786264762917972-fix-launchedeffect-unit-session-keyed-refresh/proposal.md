# Change: Key session-scoped LaunchedEffect refreshes on the session id, not Unit

Task ID: `UIX-1786264762917972`

## Why

The 2026-06-10 Compose audit found three LaunchedEffect(Unit) sites that drive ViewModel data refresh keyed on Unit:

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `fix-launchedeffect-unit-session-keyed-refresh`: Key session-scoped LaunchedEffect refreshes on the session id, not Unit

### Modified Capabilities

- None.

## Impact

- Portfolio area: `ui`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
