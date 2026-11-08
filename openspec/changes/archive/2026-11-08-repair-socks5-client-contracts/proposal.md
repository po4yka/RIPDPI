# Change: Repair SOCKS5 client contracts

Task ID: `PRX-1794125331192374`

## Why

The LOC-09 audit reproduced invalid reply acceptance, stale TUN UDP associations, an upstream setup timeout overrun, and IPv6 DNS proxy construction failure. It also found incomplete domain relay endpoints and credential validation.

## What Changes

- Reject invalid SOCKS reply headers and invalid credential byte lengths.
- Stop use of a UDP association when its control connection ends.
- Use one upstream setup deadline and correct IPv6 proxy authorities.
- Support valid domain relay endpoints without unbounded name resolution.
- Align configured DNS credentials with the owned-listener authentication policy.
- Preserve valid requests, reply error codes, socket protection, and cancellation.

## Capabilities

### New Capabilities

- `socks5-client-contracts`: client reply, authentication, endpoint, and setup contracts.

### Modified Capabilities

- `socks5-udp-association-contracts`: apply control lifetime to TUN clients as well as proxy upstreams.

## Impact

- Rust tunnel, shared SOCKS, DNS, diagnostics, proxy runtime, and runtime adapter crates.
- Kotlin engine API and service-owned probes.
- No new dependencies, configuration schema, JNI changes, or migration.
- Invalid replies and an authentication downgrade are rejected. Valid wire exchanges remain compatible.
