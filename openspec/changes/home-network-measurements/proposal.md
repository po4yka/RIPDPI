# Change: Measure IPv6 connectivity and resolver identity

Task ID: `DGN-1791482030251870`

## Why

AAAA resolution does not prove IPv6 connectivity. A domain answer is not a resolver address.

## What Changes

- Use a bounded numeric IPv6 TCP connection and active network DNS metadata.

## Capabilities

### New Capabilities

- `home-network-measurements`: measured network metadata.

### Modified Capabilities

- None.

## Impact

- App augmentation and its tests. No schema changes.
