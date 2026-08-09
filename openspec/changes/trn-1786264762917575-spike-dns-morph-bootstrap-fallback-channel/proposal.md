# Change: Spike: DNS-Morph bootstrap as fallback bootstrap channel

Task ID: `TRN-1786264762917575`

## Why

DNS-Morph (Ailabouni-Dunkelman-Bitan, CSCML 2021) splits the transport model: the handshake uses DNS port 53 while the data plane uses any underlying transport. This provides a distinct bootstrap surface whose behavior depends on middlebox port-53 handling and active L7 fingerprinting. No mature Android-targeting fork exists yet. The spike validates whether the bootstrap shim is buildable on Android and whether controlled external clients can complete the roughly 80-query type-A handshake on representative resolver paths

## What Changes

- Deliver the observable outcome and acceptance criteria recorded in the linked portfolio task.
- Preserve unrelated behavior and enforce the repository validation and evidence requirements.

## Capabilities

### New Capabilities

- `spike-dns-morph-bootstrap-fallback-channel`: Spike: DNS-Morph bootstrap as fallback bootstrap channel

### Modified Capabilities

- None.

## Impact

- Portfolio area: `transport`.
- Exact code, contracts, migrations, and validation gates are constrained by the linked task and design.
