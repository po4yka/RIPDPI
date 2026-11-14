# Change: Reject invalid IPv6 inputs

Task ID: `RST-1794677654202696`

## Why

DPI-33 found malformed routing output and silently ignored or replaced inputs.

## What Changes

- BREAKING: reject unsupported routing.
- Reject unknown explicit ext_type and inapplicable UDP profiles.

## Capabilities

### New Capabilities

- `ipv6-extension-validation`: explicit input rejection.

### Modified Capabilities

- None.

## Impact

Rust strategy config, IPv6 strategy, TUN and proxy config. No dependencies or stored schema changes.
