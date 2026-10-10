# Change: Separate IPv4, IPv6 and NAT64 probes

Task ID: `DGN-1791529750911166`

## Why

Mixed address selection can hide a failed IP family. The current short IPv6 check cannot distinguish DNS64 discovery from NAT64 connectivity, and its evidence is not available in scan history.

## What Changes

- Add an explicit raw-path diagnostic profile and full-analysis stage with independent IPv4, IPv6 and NAT64 results.
- Force each destination family without hostname fallback. Separate DNS64 prefix discovery from a connection through the discovered prefix.
- Preserve bounded evidence in scan history, localized details and private exports. Missing evidence remains unknown.
- Add optional request data without changing existing profile behavior or requiring a new service.

## Capabilities

### New Capabilities

- `separate-ip-family-probes`: independent IP destination path evidence and NAT64 discovery/connection results.

### Modified Capabilities

- None.

## Impact

Native diagnostic contracts, DNS and runner code; Kotlin catalog, request mapping and full-analysis stage; app UI, locale resources, history and exports. No new dependency or required backend. No breaking stored report migration.
