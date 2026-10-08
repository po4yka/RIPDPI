# Change: Require positive HTTP blockpage evidence

Task ID: `DGN-1791481962888112`

## Why

A bare HTTP 403 currently appears as a provider blockpage although the origin can refuse access.

## What Changes

- Preserve HTTP 403 as an ordinary HTTP status when no blockpage evidence is present.
- Require specific blocking messages for HTTP 403 and preserve fingerprint classification and HTTP 451 behavior.
- No breaking schema changes.

## Capabilities

### New Capabilities

- `http-blockpage-evidence`: distinguish HTTP refusal from positive blockpage evidence.

### Modified Capabilities

- None.

## Impact

- Rust `ripdpi-diagnostics-http`; existing result codes only.
