# Change: Reuse compatible TCP connections after payload routing

Task ID: `PRX-1794124642980782`

## Why

Delayed CONNECT establishes an upstream before success. A TLS ClientHello can
then select a different strategy group for the same destination. The runtime
currently discards that connection based on the group index. An origin that
accepts one connection cannot complete the TLS tunnel.

## What Changes

- Reuse the established connection for compatible payload routing changes.
- Apply the selected payload group to the reused connection.
- Keep reconnect behavior for incompatible connect policies or destinations.
- Add regression coverage and verify TLS against a single-accept origin.
- No breaking configuration, API or wire schema change is planned.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `http-connect-contracts`: retain a compatible upstream after payload routing.

## Impact

- Shared delayed TCP path in `ripdpi-proxy-runtime`, used by HTTP CONNECT and
  SOCKS ingress; runtime tests and native documentation.
- No new dependency, Android/JNI change or external service is needed.
