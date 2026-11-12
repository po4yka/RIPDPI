# Change: Align AWG startup and WSS validation

Task ID: `TRN-1794477195384429`

## Why
The service deadline excludes valid WSS and peer setup time. Invalid nonempty WSS URLs reach native startup.

## What Changes
- Give WS readiness 40 seconds and retain UDP 15 seconds.
- Validate WSS URLs before activation. No wire or storage changes.

## Capabilities
### New Capabilities
- `awg-startup-validation`: readiness and endpoint validation.
### Modified Capabilities
- None.

## Impact
Engine API, service, runtime-state, AWG editor and tests.
