# Change: Repair TUN packet contracts

Task ID: `VPN-1794152786002343`

## Why

The VPN-06 audit found invalid packets that reach UDP forwarding and valid IPv6 packets that fail. Separate packet owners disagree about lengths, checksums, options and fragments.

## What Changes

- Reject corrupt IPv4 headers, invalid transport boundaries and discard IPv6 options before forwarding.
- Reject overlapping IPv6 fragments and retain valid extension chains during UDP reassembly.
- Accept padding Destination Options and atomic Fragment headers for IPv6 TCP.
- Correct SYNACK checksums and reject oversized IPv6 output. Preserve failure fallback and non-root forwarding.
- No schema or dependency change. No breaking configuration change.

## Capabilities

### New Capabilities

- `tun-packet-contracts`: checked packet boundaries and consistent IPv4/IPv6 packet handling.

### Modified Capabilities

- None.

## Impact

- Rust crates ripdpi-tunnel-core and ripdpi-tunnel-intercept.
- No Kotlin, JNI, protobuf, storage or root activation change.
