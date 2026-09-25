# Change: Expose dedicated relay profile editors

Task ID: `RLY-1790339260163788`

## Why

The AnyTLS, Mieru, SSH, and AmneziaWG profile editors exist as navigation destinations, but VPN configuration has no user-facing link to them. Users must use an external launch request to create these profiles manually.

## What Changes

- Add a manual profile entry point in VPN configuration for the four existing editors.
- Preserve the existing paste and scan import actions and relay profile selection behavior.
- No breaking change.

## Capabilities

### New Capabilities

- `reach-dedicated-relay-editors`: Users can open the four profile creation editors from VPN configuration.

### Modified Capabilities

- None.

## Impact

- App Compose VPN configuration and navigation only. No Rust, service, storage, or wire contract changes.
