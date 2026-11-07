# Change: Fix SOCKS5 UDP association contracts

Task ID: `PRX-1794076309764211`

## Why

The LOC-02 audit reproduced six UDP association defects: setup can send after stop, upstream control EOF is ignored, reply authority is lost, a disabled UDP flag is ignored by several relay backends, one large SS2022 packet ends the association, and source-port rebind breaks a pinned SOCKS upstream.

## What Changes

- Cancel pending UDP setup and stop use of closed upstream associations.
- Reject unexpected upstream reply peers before logical source mapping.
- Enforce the profile UDP flag before relay carrier work.
- Drop oversized datagrams without closing healthy associations.
- Keep the UDP endpoint stable for pinned SOCKS upstreams.
- No wire schema or stored profile format changes. No new third-party package or version; the SS2022 transport reuses the existing workspace libc dependency.

## Capabilities

### New Capabilities

- `socks5-udp-association-contracts`: UDP association lifetime, reply authority, admission and size isolation.

### Modified Capabilities

None.

## Impact

- Rust proxy-runtime and its adapter, relay-core and relay-tls-transports; owning tests and local source gates.
- Kotlin admission remains in place. No JNI, protobuf, configuration schema, locale or baseline change.
