# Change: Repair fake split contracts

Task ID: `ENG-1794499735949864`

## Why

Partial failures can retain fake bytes and lose send progress. Custom orders drop secondary profiles. Raw sends apply fake-only options to real data. Smoke checks do not prove the complete wire result.

## What Changes

- Preserve real queued bytes and known or uncertain send progress.
- Preserve profiles and restrict fake MD5 and TTL to fake packets.
- Verify deterministic wire bytes independently and keep error context.

## Capabilities

### New Capabilities

- `fake-split-send-contracts`: safe send failure and complete acceptance.

### Modified Capabilities

- None.

## Impact

Rust privileged operations, runtime adapters, error classification, Android tests and smoke scripts. No new dependency, stored setting or helper wire schema.
