# Change: Keep Home measurements in one network scope

Task ID: `DGN-1791484460786370`

## Why

Home adds network measurements after native stages. A network change during this phase can attach results to the wrong network and preserve stale recommendations.

## What Changes

- Check the original physical epoch and fingerprint through Home finalization.
- Drop network authority when scope is unavailable or changed; keep local findings.
- Leave captive portal state unknown when capabilities are unavailable.

## Capabilities

### New Capabilities

- `home-measurement-scope`: Scope late Home measurements to the original network.

### Modified Capabilities

- None.

## Impact

- Kotlin diagnostics and app adapters. No breaking serialized contract or new dependency.
