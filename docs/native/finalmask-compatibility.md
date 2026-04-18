# Finalmask Compatibility

RIPDPI applies Finalmask directly in the native xHTTP transport on supported relay profiles.

## Supported Today

Supported relay families:

- VLESS Reality with `xhttp`
- Cloudflare Tunnel profiles that resolve through the xHTTP TLS path

Supported modes:

- `header-custom`
- `noise`
- `fragment`
- `Sudoku`

The current implementation mutates the outbound xHTTP byte stream before the Reality or TLS handshake starts.

## Control Plane Integration

Finalmask is now carried through:

- relay profile models and protobuf-backed settings
- advanced relay editor fields
- strategy-pack feature flags and presets
- Kotlin service validation
- Rust relay validation

Unsupported combinations fail fast instead of being silently ignored.

## Not Supported Yet

The current tree still does not implement:

- QUIC-side Finalmask
- Hysteria2 Finalmask
- TUIC Finalmask
- MASQUE Finalmask

New Finalmask transport or mode work should not be enabled until upstream-compatible parity tests exist.

## Example Configs

Use these examples as references for compatible server-side setups and matching client profiles:

- [xHTTP server example](../examples/finalmask/xhttp-finalmask-server.json)
- [Cloudflare Tunnel server example](../examples/finalmask/cloudflare-tunnel-finalmask-server.json)
- [xHTTP client profile](../examples/finalmask/xhttp-client-profile.json)
- [Cloudflare Tunnel client profile](../examples/finalmask/cloudflare-tunnel-client-profile.json)

## Practical Notes

- Cloudflare Tunnel profiles still force xHTTP and disable UDP, so they can reuse the same Finalmask path as other xHTTP-backed profiles.
- Finalmask rollout should remain strategy-pack controlled rather than becoming an unconditional default.
