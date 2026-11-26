# Change: Skip non-surviving bundle entries when allowlist mode is detected

Task ID: `DAT-1795704964813672`

## Why

When allowlist mode is detected, failover skips bundle entries whose allowlist-regime survivability label says they do not survive, and treats unlabeled entries as neutral.

## What Changes

- The bundle parser reads the survivability label per entry, and golden tests cover labeled, unlabeled and malformed values.
- In allowlist mode, failover ordering excludes entries labeled as not surviving and keeps unlabeled entries neutral.
- Outside allowlist mode, the label does not change ordering.
- The drift pin matches the vendored schema that carries the label.

## Capabilities

### New Capabilities

- `dat-1795704964813672-skip-non-surviving-bundle-entries-when-allowlist-mode-is-detected`: Skip non-surviving bundle entries when allowlist mode is detected

### Modified Capabilities

- None.

## Impact

- Portfolio area: `data`.
- Filed at proposal level only. Design and implementation decisions are open until the task starts.
