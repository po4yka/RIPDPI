# Change: Preserve extended DNS response semantics

Task ID: `DGN-1791527373647141`

## Why

DNS diagnostics lose negative answers and response metadata. An empty answer can become a transport failure or a blocking claim. Users need response facts to distinguish DNS conditions from later connection failures.

## What Changes

- Preserve per-source query type, response code, negative answer kind, truncation, CNAME targets, TTL bounds and numeric extended error codes.
- Show response semantics in current and historical probe details and exports, with explicit missing evidence for older reports.
- Keep resolver disagreement separate from proof of provider interference. Correct the list-based DNS tool fallbacks that currently overstate blocking.
- No breaking request, database or native schema version change. New observation fields are optional.

## Capabilities

### New Capabilities

- `extended-dns-semantics`: retain and explain query-bound DNS response evidence.

### Modified Capabilities

- None.

## Impact

- Native diagnostic DNS parsing, runner and observation mapping; Kotlin diagnostic models, exports and DNS tools; app evidence cards and ten locales.
- No production dependency, target catalog, runtime DNS policy or required backend change.
