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

The task adds fake-split profile/context fixes and acceptance tests/scripts. It uses already published shared raw/helper safety and packet-role fixes for F1/F3/F4. Two unique Linux failure-path tests extend that coverage. No new dependency, stored setting or helper wire schema belongs to this task.
