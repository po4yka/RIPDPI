# Change: Complete proxy URI import

Task ID: `OUT-1794722656313653`

## Why

IMP-01 found lost TLS pins, lost saved profiles, lossy exports, changed SSH credentials and secret-bearing scanner errors. TUIC links stop at preview. Mieru advanced fields are ignored. Users need saved profiles that retain the settings in their links.

## What Changes

- Fix audit findings F1-F7 with one code commit per finding.
- Store each confirmed import and its secrets atomically, including group membership and its display name.
- Add typed TUIC v5 URI import, native admission, persistence and export for existing runtime capabilities.
- Add Mieru v3.36.0 advanced TCP settings from URI to actual native behavior and export.
- BREAKING: reject ambiguous query collisions and invalid advanced fields that older code silently discarded. Advance the native config schema if its existing version contract requires it.

## Capabilities

### New Capabilities

- `proxy-uri-import`: Safe parsing, durable import, round-trip export and bounded native protocol support.

### Modified Capabilities

- None. The new requirements use the existing atomic-profile-persistence contract.

## Impact

- app import/share/scanner, core:data runtime-state and persistence, core:service relay resolution, core:engine-api and native codec.
- Rust ripdpi-mieru and relay config/backend plumbing; no new production dependencies or AppSettings protobuf fields.
