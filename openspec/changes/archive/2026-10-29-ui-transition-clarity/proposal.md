# Change: Clarify connection feedback and reconnect changes

Task ID: `UIX-1792244042561922`

## Why

History slides use physical direction without RTL. Connection status swaps its
indicator abruptly. Reconnect confirmation states that settings changed without
showing the safe applied and saved values being compared.

## What Changes

- Mirror History section motion for RTL while preserving reader state.
- Show a short status transition with a stable indicator slot and available Cancel.
- Compare safe applied and saved settings before reconnect, with explicit unknown
  and saved-intent wording and stale-preview invalidation.

## Capabilities

### New Capabilities

- `ui/transition-clarity`: reading-direction motion, connection feedback, and safe reconnect preview.

### Modified Capabilities

- None. No breaking contract changes.

## Impact

- App UI, app-only presentation models, tests, locale resources and translation manifest.
- Existing reconnect admission, runtime confirmation, service and persistence contracts remain authoritative.
- No production dependency, native, wire, storage, or backend change.
