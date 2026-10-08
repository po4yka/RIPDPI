# Change: Keep generic direct failures unattributed

Task ID: `DGN-1791484568964479`

## Why

Failed attempts alone do not prove that an IP address is blocked.

## What Changes

- Preserve a failed direct attempt as unknown cause with no IP transport class.
- Keep the measured no-solution outcome and cooldown.

## Capabilities

### New Capabilities

- `direct-failure-evidence`: bounded failure attribution.

### Modified Capabilities

- None.

## Impact

DirectModePolicySupport and tests. No wire, storage or dependency changes.
