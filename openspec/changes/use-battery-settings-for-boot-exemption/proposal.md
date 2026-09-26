# Change: Open battery settings for boot exemption

Task ID: `AND-1790434316820665`

## Why

Start on boot offers `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` before the
general settings screen. Android requires a manifest permission for that direct
action, but the app does not declare it. The direct candidate is ineffective
and Android lint reports a policy warning.

## What Changes

- Start on boot opens the general battery optimization settings instead of
  requesting a direct exemption.
- Vendor autostart screens and app details remain ordered fallbacks.
- The user opt-in and warning banner remain available.
- No breaking changes.

## Capabilities

### New Capabilities

- `boot-battery-guidance`: Start on boot guides users through a working
  battery settings action without a direct exemption request.

### Modified Capabilities

- None.

## Impact

- `:app` Start on boot intent selection and focused tests. No new permission,
  dependency, resource key, or data contract.
