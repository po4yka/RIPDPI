# Change: Correct connectivity diagnostics and protocol configuration

Task ID: `DGN-1788582590436769`

## Why

The source audit found false network results, incomplete protocol frames, missing authentication checks, and lost settings updates. These faults can prevent a working connection or report a broken path as healthy.

## What Changes

- Preserve target address fallback, validate UDP and QUIC responses, and measure DNS on the current path.
- Correct SOCKS writes, Shadowsocks TCP and UDP framing, DoQ IDs, and TLS signature checks.
- Preserve atomic settings changes and coroutine cancellation; contain storage and malformed package errors.
- Record component coverage, observed checks, and remaining device and upstream conformance gaps.
- No JNI, protobuf, stored settings, or diagnostics schema changes.

## Capabilities

### New Capabilities

- `connectivity-protocol-integrity`: regression requirements for network measurements, relay framing, and settings activation.

### Modified Capabilities

- None. Existing configuration formats and outcome tokens remain stable.

## Impact

Native diagnostics, packets, DNS, SOCKS and Shadowsocks crates; Android settings and engine readiness; support settings UI; local protocol test server; audit documentation and test tools. No new dependencies or backend service.
