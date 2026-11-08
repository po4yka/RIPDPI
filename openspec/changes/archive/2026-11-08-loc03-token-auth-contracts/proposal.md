# Change: Enforce SOCKS5 token authentication contracts

Task ID: `PRX-1794114937480022`

## Why

The LOC-03 audit found four reproducible defects in RFC 1929 configuration, reply validation and loopback correlation. Invalid configurations can start but cannot authenticate. The TUN client accepts an invalid response version or emits empty fields.

## What Changes

- Reject unrepresentable tokens before a SOCKS5-capable listener binds.
- Reject malformed TUN auth replies and invalid credential lengths.
- Keep optional correlation for IPv4-mapped loopback peers.
- Compatibility: invalid configurations and malformed peers now fail earlier. Valid clients keep their existing wire format. HTTP-only token length remains unchanged.

## Capabilities

### New Capabilities

- `socks5-token-auth`: bounded token framing, strict client replies and canonical loopback correlation.

### Modified Capabilities

None.

## Impact

Rust crates: ripdpi-proxy-runtime, ripdpi-tunnel-core, ripdpi-tunnel-config. No new dependencies, JNI fields, wire versions or storage migrations. The user authorized separate fix commits, main integration and push.
