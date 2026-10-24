# Change: Route active AWG profile DNS through its tunnel

Task ID: `RTE-1792840535644306`

## Why

The default Direct LAN rule matches private DNS addresses in a standalone AWG profile. The native destination decision then removes the AWG SOCKS upstream. Physical acceptance authenticates the AWG handshake but cannot reach the configured in-tunnel DNS server.

## What Changes

- Route TCP and UDP on ports 53 and 853 to exact DNS addresses from the active standalone AWG profile through its tunnel.
- Preserve the effective Block result and all other destination policy decisions.
- Freeze resolver addresses with the runtime configuration and remove this authority when AWG is not active.
- Add a defaulted native JSON field within current schema version 2; no persistent schema, JNI signature, or dependency change.

## Capabilities

### New Capabilities

- `awg-profile-dns-routing`: scoped resolver routing for the active AWG profile.

### Modified Capabilities

- None.

## Impact

- Engine API, engine codec/projection, service composition, native proxy configuration, and destination egress evaluation.
- Physical Android acceptance remains required. Do not alter the peer DNS addresses or disable the LAN rule to obtain a passing test.
