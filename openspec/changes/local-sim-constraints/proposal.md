# Change: Explain local device and SIM constraints

Task ID: `DGN-1791525730340153`

## Why

Users need to distinguish local settings and mobile subscription state from a remote connectivity failure. Current diagnostics lack SIM readiness and mobile-data settings, and cellular metadata disappears under Wi-Fi or VPN.

## What Changes

- Capture local settings and default-data SIM evidence independent of the active transport.
- Show observed conditions, their limited scope and unavailable evidence in current, live and historical diagnostics.
- Export categorical evidence without subscriber identifiers. Keep old reports readable.
- No breaking changes, new permissions or required external services.

## Capabilities

### New Capabilities

- `local-sim-constraints`: bounded local and default-data SIM evidence and explanations.

### Modified Capabilities

None.

## Impact

- Kotlin diagnostics collection, optional persisted context, UI and all ten locales, redacted JSON/text export.
- No native wire, Room, fingerprint, dependency or manifest migration.
