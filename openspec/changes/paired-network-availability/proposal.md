# Change: Compare selective availability across explicit network sessions

Task ID: `DGN-1795014097920678`

## Why

The selective availability matrix DGN-1791519134625558 already measures bounded target cohorts and payloads; ComparisonScanCoordinator compares raw versus in-path runs. Extend those existing owners with explicit user-selected cross-network session pairing rather than add another scanner.

## What Changes

- Add the bounded diagnostic behavior specified below, manually initiated and offline-first.
- Preserve current default behavior and distinguish observations from cause hypotheses.

## Capabilities

### New Capabilities

- `paired-network-availability`: scoped diagnostic comparison evidence.

### Modified Capabilities

- None; integrate through existing diagnostic owners without weakening their guarantees.

## Impact

Existing native diagnostics runner/probes/contracts/classification, core diagnostics/history/export and manual diagnostic UI. Future schema changes require synchronized Rust/Kotlin updates. No new required backend.

## Sources

https://github.com/vadim-tolmachev/allowlist-probe

The pilot motivates same-device near-time controls and distinguishing a dead endpoint from path-specific failure. Its small sample is not country-wide proof; neither public endpoint lists nor claimed permitted ASNs are trusted inputs.
