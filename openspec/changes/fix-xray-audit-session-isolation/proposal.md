# Change: Isolate Xray UDP sessions and release unavailable stub ownership

Task ID: `RLY-1794335291032951`

## Why

The pinned Xray UDP filter grants access by loopback IP after one authenticated control connection. Other local processes share that IP. The unavailable Xray stub also leaves the process runtime owner occupied after startup fails.

## What Changes

- Require the existing private RUF1 association and session nonce for managed Xray UDP.
- Bind each association to its authenticated control, source endpoint and lifetime.
- Update authenticated local diagnostic clients to use this existing private hop.
- Let unavailable stub cleanup release its empty owner while preserving linked cleanup failure ownership.
- BREAKING: the repository-owned managed Xray inbound rejects ordinary UDP ASSOCIATE. Generic external SOCKS listeners keep their standard behavior.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `run-xray-as-managed-vpn-relay-runtime`: authenticated UDP session isolation and clean unavailable-runtime failure.
- `socks5-udp-association-contracts`: local diagnostic client session negotiation.

## Impact

- Pinned Xray-core source patch, patch digest, host contract tests and diagnostic Rust transport.
- Xray stub implementation and owning unit tests.
- No upstream release bump, dependency addition, production credential action or public relay protocol change.
