# Change: Fix TUN UDP error quotes and local failure delivery

Task ID: `VPN-1794142179721255`

## Why

VPN-04 has a test-only error serializer. It rebuilds quoted packets and truncates IPv6 quotes too early. A local SOCKS association failure currently closes without TUN feedback.

## What Changes

- Preserve the original validated UDP/IP prefix in ICMP errors.
- Use the IPv6 minimum MTU budget for quotes.
- Report a deterministic local association failure from the local TUN address as Host Unreachable (IPv4) or Address Unreachable (IPv6).
- Keep timeout, congestion, cancellation, policy drops and uncorrelated receive errors silent. Do not claim remote UDP Port Unreachable over SOCKS5.
- No breaking JNI, storage, config or SOCKS wire change.

## Capabilities

### New Capabilities

- `vpn-udp-error-originating`: bounded, validated local UDP error delivery and exact original quotes.

### Modified Capabilities

- None.

## Impact

- ripdpi-tunnel-core packet and UDP loop internals, owning tests and docs/native/tunnel.md.
- No new dependency or backend, no root requirement, no external feedback channel.
