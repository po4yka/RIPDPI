# Require a completed transfer for the active strategy verdict

Task ID: `DGN-1795277862388284`

## Why

The active strategy can be reported as working after a TLS handshake even if the response body would stall.

## What Changes

- Measure a bounded HTTPS transfer to one configured control on the same owned diagnostic route.
- Report the last confirmed stage. Require a completed body for the active path's working verdict.

## Capabilities

### New Capabilities

- `diagnostic-transfer-verdict` — Evidence of a completed transfer through the active diagnostic path.

### Modified Capabilities

- None.

## Impact

The active path verdict in an in-path strategy scan. Relay learning is outside this change.
