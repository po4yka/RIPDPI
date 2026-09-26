# Change: Block deep links until app unlock

Task ID: `AND-1790430183226560`

## Why

An external link can navigate to app content while the biometric gate or onboarding is visible. This exposes screens before local authentication and defeats the selected app lock.

## What Changes

- Defer deep-link navigation until the current gate has cleared.
- Route supported cold and warm deep links through the same gated path.
- Prevent Navigation from acting directly on the Activity's inbound intent.

## Capabilities

### New Capabilities

- `app-lock-navigation`: Deep links respect onboarding and local authentication gates.

### Modified Capabilities

- None.

## Impact

- `:app` navigation and Activity launch handling; no wire, storage, or service contracts change.
