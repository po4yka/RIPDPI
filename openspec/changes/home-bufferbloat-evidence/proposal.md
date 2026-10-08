# Change: Require overlapping transfer for bufferbloat grades

Task ID: `DGN-1791482073167643`

## Why

A failed or nonoverlapping download does not create a measured load.

## What Changes

- Report unknown bufferbloat unless successful data transfer overlaps RTT samples.

## Capabilities

### New Capabilities

- `home-bufferbloat-evidence`: validated bufferbloat load evidence.

### Modified Capabilities

- None.

## Impact

- App augmentation and tests. No schema change.
