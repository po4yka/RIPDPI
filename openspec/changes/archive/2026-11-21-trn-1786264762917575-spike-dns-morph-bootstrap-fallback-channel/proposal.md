# Change: Spike: DNS-Morph bootstrap as fallback bootstrap channel

Task ID: `TRN-1786264762917575`

## Why

DNS-Morph (Ailabouni-Dunkelman-Bitan, CSCML 2021) splits the transport model: the handshake uses DNS port 53 while the data plane uses any underlying transport. This provides a distinct bootstrap surface whose behavior depends on middlebox port-53 handling and active L7 fingerprinting. No mature Android-targeting fork exists yet. The spike validates whether the bootstrap shim is buildable on Android and whether controlled external clients can complete the roughly 80-query type-A handshake on representative resolver paths

## What Changes

- The deploy role names no bridge source, wire format or recipient export, so RIPDPI defines DNS-Morph itself (owner decisions, 2026-11-20 and 2026-11-21): token-authorized, sealed and signed subscription delivery over UDP TXT (v2), not a traffic tunnel.
- New crate `ripdpi-dns-morph` holds the client and the bridge responder; the Android platform adapter exposes it through `NativeDnsMorphFetcherNativeBindings.jniFetch` with a protected socket.
- The `ripdpi.dns_morph` bundle block declares the bridge (IP literal, port, zone, Ed25519 public key, subscriber token). A subscription refresh uses it only after every HTTPS endpoint failed.
- `docs/server-integration.md` records the wire contract and the deploy-side work a live bridge needs.

## Capabilities

### New Capabilities

- `spike-dns-morph-bootstrap-fallback-channel`: Spike: DNS-Morph bootstrap as fallback bootstrap channel

### Modified Capabilities

- None.

## Impact

- Portfolio area: `transport`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
