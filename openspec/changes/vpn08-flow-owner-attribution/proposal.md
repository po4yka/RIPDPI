# Change: Preserve VPN flow owner identity

Task ID: `VPN-1794152738556618`

## Why

The VPN-08 audit found stale UDP owners, destination collisions, stale callbacks, startup request loss, and lost MapDNS attribution.

## What Changes

- Bound UDP UID age independently of traffic.
- Keep owner results within the TUN session and package revision that requested them.
- Start the attribution worker before TUN packet processing.
- Carry an opaque app scope through authenticated local SOCKS and learning signals. Separate learning state by app scope.
- Preserve app attribution through synthetic DNS address translation.
- BREAKING internal JNI callback result changes with its callers. Runtime telemetry adds an optional backward-compatible appScope field within schema 3. No user configuration or stored-record migration.

## Capabilities

### New Capabilities

- `vpn-flow-owner-identity`: bounded, session-owned flow attribution and learning.

### Modified Capabilities

- None.

## Impact

core service, engine and engine-api; data model; native attribution, TUN, proxy runtime, runtime policy, ports and telemetry adapters. No dependencies, locales, baselines or external services.
