# Change: Verify diagnostic evidence before using it

Task ID: `DGN-1795238144093160`

## Why

Old proxy signals can change a new network policy. Diagnostics can also combine unrelated SNI results or apply a DNS change from weak evidence.

## What Changes

- Bind direct-path learning to the proxy run and physical network that produced its observations.
- Mark revoked VPN-route observations as partial and withhold route-dependent advice.
- Keep SNI facts per target. Require verified same-query recovery for an automatic DNS update.

## Capabilities

### New Capabilities

- `diagnostic-evidence-authority` — Check the source and scope of diagnostic evidence before applying it.

### Modified Capabilities

- None.

## Impact

The change affects the proxy supervisor, diagnostics finalization, DNS recommendations, and native classification. Existing persisted network keys and external wire formats stay in use.
