# Change: Report exhausted UDP DNS timeouts without recovery claims

Task ID: `DGN-1791482054896448`

## Why

Repeated UDP timeouts with no successful response are labeled transient although no recovery occurred.

## What Changes

- Exhausted UDP timeout probes report DNS unavailability with cause left uncertain.
- Recovered retries retain the existing unstable UDP DNS outcome.
- No breaking schema change.

## Capabilities

### New Capabilities

- `udp-dns-timeout-evidence`: distinguish failed retries from observed recovery.

### Modified Capabilities

- None.

## Impact

- Rust `ripdpi-diagnostics-runner`; existing outcome tokens and evidence details.
