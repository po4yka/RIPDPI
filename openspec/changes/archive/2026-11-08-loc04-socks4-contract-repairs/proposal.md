# Change: Repair SOCKS4 and SOCKS4a protocol contracts

Task ID: `PRX-1794116241356560`

## Why

The LOC-04 audit reproduced false CONNECT success, data loss after an empty FIN,
an IPv6 policy bypass, stalled DNS during stop, inconsistent sentinel parsing,
and missing failure replies at the request size limit.

## What Changes

- Send SOCKS4 CONNECT success only after a usable upstream connection exists.
- Preserve both directional FIN states before and after the first payload.
- Reject IPv6 targets when IPv6 is disabled.
- Cancel handshake DNS on runtime stop and the handshake deadline.
- Use the exact SOCKS4a sentinel and validate the parser input boundary.
- Send a SOCKS4 rejection when a complete request exceeds the local size limit.
- Keep CONNECT-only operation, domain limits, auth gates, and resource bounds.

## Capabilities

### New Capabilities

- `socks4-connect-contracts`: SOCKS4 and SOCKS4a request, connection, and close contracts.

### Modified Capabilities

- None. Shared TCP relay and DNS code must preserve existing SOCKS5 and HTTP behavior.

## Impact

- Rust session parser, proxy handshake, TCP relay, handshake DNS callers, and the WS transport port.
- Add a prepared WS session contract so the carrier can open before SOCKS4 ACK.
- No JNI, protobuf, storage, configuration schema, dependency, or locale changes.
- Numeric IPv6 targets now fail when IPv6 is disabled. This repairs a policy bypass.
