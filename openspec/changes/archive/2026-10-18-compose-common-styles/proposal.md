# Change: Migrate RDS components to Compose CommonStyle

Task ID: `UIX-1792228984398359`

## Why

Shared controls repeat visual state collection and animation wiring. A common style layer can apply these properties in layout and drawing while retaining RDS tokens and interaction behavior.

## What Changes

- Add optional visual overrides to eligible RDS components without changing defaults.
- Move RDS-owned container styling to the current CommonStyle API.
- Preserve Material text parameters, accessibility, motion policy, and feature geometry.
- Update Compose to the published 1.13 beta set and compile SDK to 37.1.
- No intentional user-visible or persisted breaking changes.

## Capabilities

### New Capabilities

- `rds-common-styles`: Apply theme-aware component styles with explicit state and override rules.

### Modified Capabilities

- None.

## Impact

- App UI and tests, version catalog, Android convention plugins, and UI benchmarks.
- No native, JNI, protobuf, storage, network, or service behavior changes.
- The API is experimental. The implementation must not use the transitional Style/styleable adapters.
