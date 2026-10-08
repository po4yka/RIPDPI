# Change: Fetch subscription profiles before confirming import

Task ID: `DAT-1791480858535857`

## Why

Ordinary subscription confirmation stores a URL and immediately reports success with no profiles. The user must find a separate manual refresh action before use.

## What Changes

- Fetch and persist ordinary subscription content after the user confirms Add.
- Report success only after a successful refresh, including AWG-only content.
- Keep failed imports on the confirmation screen and reuse their group on retry.
- Reuse manual recovery for existing long-lived URLs and preserve bootstrap single-use behavior.

## Capabilities

### New Capabilities

- `subscription-initial-fetch`: Confirmation completes only after profiles reach storage.

### Modified Capabilities

- None.

## Impact

App confirmation ViewModel, injected refresh coordinator, and tests. No automatic connection, new backend, dependency, or storage schema.
