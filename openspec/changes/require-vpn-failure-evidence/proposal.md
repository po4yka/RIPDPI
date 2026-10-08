# Change: Require evidence for VPN failure

Task ID: `DGN-1791482086226598`

## Why

The default service state is Halted/VPN. An offline first launch converts this default into evidence of a failed VPN session.

## What Changes

Preserve the native network snapshot without inferring prior VPN activity from the configured mode.

## Capabilities

### New Capabilities

- `vpn-failure-evidence`: preserve explicit network evidence.

### Modified Capabilities

- None.

## Impact

Kotlin scan context collection and unit tests. No schema or dependency changes.
