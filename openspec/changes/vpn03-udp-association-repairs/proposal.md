# Change: Repair UDP association contracts

Task ID: `VPN-1794141873537976`

## Why

The VPN-03 audit found incompatible MapDNS UDP handoff to Xray, lost reply mappings, retained historical DNS leases, incomplete large-datagram delivery, and missing UDP checksum validation. These faults can drop permitted traffic or exhaust the shared DNS cache.

## What Changes

- Use a compatible local-hop authority contract for native and Xray providers.
- Keep admitted reply mappings valid and bound per-source DNS ownership.
- Deliver complete normal UDP messages through IPv4 and IPv6 within the TUN MTU.
- Reject malformed UDP checksums before forwarding.
- Verify the reported concurrency and source-lifetime risks before changing them.
- No user settings or production dependencies are added. No external server or root operation is required for local verification.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `socks5-udp-association-contracts`: bound authority ownership, retain message boundaries, and validate ingress.
- `bridge-tun-traffic-through-xray-local-inbound`: preserve resolved target authority with a compatible UDP local hop.

## Impact

- Rust tunnel-core, tunnel configuration and Android configuration projection when required by the provider contract.
- Kotlin VPN/Xray composition and tests when required by the provider contract.
- No deliberate public wire or stored settings break. Internal additive contract changes must update both producers and consumers together.
