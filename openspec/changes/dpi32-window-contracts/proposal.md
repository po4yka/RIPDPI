# Change: Repair TCP window configuration contracts

Task ID: `PRX-1794677617943037`

## Why

DPI-32 has buffer overflow, a false exact-scale promise, silent socket fallback, and a weak wire assertion. Window YAML is accepted without an executor.

## What Changes

- Define scale as the existing buffer shift hint. Preserve stored field numbers.
- Reject invalid active values and preserve automatic scale through settings.
- Report redacted option outcomes with readback while preserving real payload fallback.
- BREAKING: Reject unsupported standalone window YAML and invalid active CLI windows instead of a silent no-op. UI zero remains disabled.
- Verify negotiated effective windows.

## Capabilities

### New Capabilities

- `tcp-window-control`: Bounded socket requests, honest outcomes, and explicit admission.

### Modified Capabilities

- None.

## Impact

- Rust config, socket adapters, proxy tests, and Kotlin settings mapping.
- No dependency, packet rewrite, root activation, stored field number, or telemetry wire schema change.
