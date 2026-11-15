# Change: Repair UDP contracts

Task ID: `RST-1794723522152914`

## Why

Repair six DPI-34 audit findings in UDP sockets, rebind, raw sidecars and documentation.

## What Changes

- Isolate sockets and admit only QUIC rebind.
- BREAKING: reject negative udplen and make zero inert.
- Support safe IPv6 options.
- Correct documentation.

## Capabilities

### New Capabilities

- `udp-socket-sidecar-contracts`: safe UDP ownership and sidecars.

### Modified Capabilities

- None.

## Impact

Rust proxy, config, Lua, UDP strategy and TUN interceptor. No new dependencies or privileges.
