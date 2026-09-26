# Change: Correct QUIC and STUN reachability probes

Task ID: `DGN-1790430642039687`

## Why

The Android DPI suite sends a synthetic 125-byte QUIC packet to its bundled `cloudflare.com` target. A compliant server cannot treat it as a valid QUIC Initial, so a reachable path can appear blocked. The Snowflake STUN probe treats any 20-byte UDP response as success, which can report availability for unrelated traffic.

## What Changes

- The DPI suite sends valid native QUIC Initial packets for every target, including `cloudflare.com`.
- A packet-generation failure does not become a network-block verdict.
- Snowflake STUN reachability requires a matching binding success response from the requested endpoint.
- No breaking wire or storage contract changes.

## Capabilities

### New Capabilities

- `diagnostics/network-probe-response-integrity`: QUIC and STUN verdicts require valid request and response evidence.

### Modified Capabilities

- None.

## Impact

- Android DPI suite wiring in `:app` and QUIC/STUN probes plus focused tests in `:core:diagnostics`.
- No new dependency, JNI signature, schema, or generated native artifact.
