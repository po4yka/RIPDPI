# Change: Release DNS capacity before result publication

Task ID: `DNS-1788602983485108`

## Why

The executor publishes a lookup result while its capacity permit is still held. An immediate next request can receive Busy after the previous lookup has finished. Full CI reproduced this existing race.

## What Changes

- Drop the existing permit before sending the result.
- Preserve concurrency limits, deadlines and error types.

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `connectivity-protocol-integrity`: completed lookup capacity precedes result publication.

## Impact

Only the diagnostics transport executor changes. No public API, configuration, wire or dependency changes.
