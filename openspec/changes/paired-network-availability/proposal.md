# Change: Compare selective availability across explicit network sessions

Task ID: `DGN-1795014097920678`

## Why

The selective availability matrix (`DGN-1791519134625558`) measures target cohorts on one network. A target that fails on mobile data can be blocked on that path or simply down. A second run of the same matrix on another network, close in time, separates the two cases. `ComparisonScanCoordinator` already compares raw and in-path runs on one network; it does not pair sessions across networks.

## What Changes

- Record the revisions a matrix session depends on: target set, probe parameters, and app configuration.
- Let the user pick two saved matrix sessions from different networks and compare them when their revisions match and their age difference is within the limit.
- Show per-target differences with control rows outside the failure denominator.

## Capabilities

### New Capabilities

- `paired-network-availability`: comparison of two explicit selective-matrix sessions.

### Modified Capabilities

- None.

## Impact

`core/diagnostics` (`SelectiveMatrixEvidence.kt`, history persistence, `DiagnosticsRedactedSummaries.kt`), diagnostics UI and its strings in all locales. The native probe does not change unless the revision inputs are not available in Kotlin. Adding revisions to stored sessions is a persistence change.

## Sources

https://github.com/vadim-tolmachev/allowlist-probe

The pilot uses same-device, near-time controls to tell a dead endpoint from a path-specific failure. Its sample is small. Its endpoint lists and permitted-ASN claims are not inputs.
