# Change: Repair VPN-02 TCP stack regressions

Task ID: `VPN-1794140727483999`

## Why

The VPN-02 audit reproduced data loss on half-close, missing cleanup after idle expiry, repeated initial sequence numbers, ignored TCP buffer configuration, and an IPv6 default MSS error. These faults affect the TUN-to-SOCKS byte bridge.

## What Changes

- Drain both TCP directions and retain close state after relay completion.
- Retire failed relay sessions after upstream EOF.
- Seed each interface from OS entropy and fail setup if entropy is unavailable.
- Apply the existing TCP buffer setting to native RX/TX buffers.
- Correct IPv6 default MSS in the existing pinned smoltcp dependency.
- No schema, user interface, root, or persistence changes.

## Capabilities

### New Capabilities

- `vpn-tcp-stream-integrity`: reliable TUN TCP stream and session resource contracts.

### Modified Capabilities

- None.

## Impact

Rust tunnel-core, existing smoltcp dependency source patch, native lockfile, and regression tests. No new dependency package or remote service. No breaking config change.
