# Change: Repair Apps Script relay boundaries

Task ID: `RLY-1794300111377171`

## Why

RLY-15 has an incompatible local authentication path and unsafe HTTP and trust boundaries.

## What Changes

- Enforce session authentication, reject implicit direct fallback and unsupported CORS preflight.
- Preserve HTTP fields and reject invalid envelopes.
- Provide private CA storage and public certificate provisioning.
- Remove the unused parallel control. BREAKING: unsafe fallback and fabricated CORS permissions are removed.

## Capabilities

### New Capabilities

- `apps-script-relay`: authenticated HTTP relay and explicit trust boundaries.

### Modified Capabilities

- `standard-components/separate-cors-sites-with-public-suffix-list`: Apps Script no longer fabricates origin permission.

## Impact

Native Apps Script core, service/engine config, connection UI, locales and peer documentation. No new dependency or production deployment.
