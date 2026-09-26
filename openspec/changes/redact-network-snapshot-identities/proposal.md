# Change: Exclude raw network identities from diagnostic snapshots

Task ID: `DGN-1790433240059940`

## Why

Production network snapshots store raw SSID, BSSID, operator identities, DNS and local addresses, and private DNS hostnames in diagnostic history. This conflicts with the repository privacy contract.

## What Changes

- All production snapshot capture paths receive a storage-safe network snapshot.
- Snapshot summaries retain transport, address counts, and coarse network status.
- Public-IP collection and runtime public-IP fields remain subject to a separate product decision.
- Previously stored snapshots still require a separate data migration. This change closes only new writes.

## Capabilities

### New Capabilities

- `diagnostics/stored-network-snapshot-privacy`: Capture-time projection of identifying network fields.

### Modified Capabilities

- None.

## Impact

- `:core:diagnostics` network metadata provider and tests.
- Existing `network_snapshots.payloadJson` rows are not rewritten by this change.
