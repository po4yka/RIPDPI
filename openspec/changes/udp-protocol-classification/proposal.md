# Change: Connect existing UDP protocol classification

Task ID: `RST-1790681076965083`

## Why

TUN protocol filters for QUIC, DTLS, STUN, DHT, and WireGuard currently accept every UDP packet. TUN Lua receives Unknown for non-QUIC UDP even though the shared classifier already detects these protocols. A protocol-scoped rule can therefore change unrelated traffic.

## What Changes

- Match UDP rules against the detected payload protocol.
- Give TUN strategies the existing typed UDP classification and WireGuard/DTLS details.
- Preserve QUIC version, hostname, markers, transport ports, and TCP behavior.
- Unknown UDP passes unless a rule explicitly accepts any protocol. No schema or external dependency changes.

## Capabilities

### New Capabilities

- `udp-protocol-classification`: typed UDP matching and TUN strategy context.

### Modified Capabilities

- None.

## Impact

- Rust tunnel interceptor and its existing protocol-detect workspace dependency.
- Rules that previously depended on incorrect cross-protocol UDP matching must use an empty or Any protocol filter.
- No JNI, Kotlin, persisted configuration, or root-backend contract changes.
