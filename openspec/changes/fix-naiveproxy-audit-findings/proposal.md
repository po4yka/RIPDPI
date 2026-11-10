# Change: Fix NaiveProxy audit findings

Task ID: `RLY-1794300094981437`

## Why

The RLY-12 audit found that the Chromium launcher can discard a reachable IPv6 address, proxy failures can leave healthy telemetry, and invalid Basic usernames can change the authenticated identity. Two owning documents also describe obsolete runtime and local-auth behavior.

## What Changes

- Preserve the ordered encrypted-resolver result.
- Report known proxy-side errors as classified failures.
- Reject invalid Basic credentials before launch and at the helper boundary.
- Update the two owning documents.
- Existing invalid profiles with colon usernames or ASCII controls are refused. Valid profiles keep their behavior.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `naiveproxy-client-runtime`: address-order, failure-health, and credential validation guarantees.

## Impact

- Runtime-state, app editor validation, Android service orchestration, and the Rust NaiveProxy helper.
- No schema, dependency, locale, or artifact pin change.
