# Change: Validate geo databases before activation

Task ID: `ENG-1794755247754263`

## Why

Geo rules can silently miss valid upstream regular expressions or corrupted MMDB records. Asset installation can replace a working database with an invalid file. Stored release tags can describe a different local file.

## What Changes

- Preserve supported Go regex syntax and flags and sing-box suffix boundaries.
- Reject invalid complete databases before publication and preserve working snapshots on errors.
- Bind update status to the installed database and its source.
- Retain offline operation, 64 MiB delivery limits, and restart-to-apply behavior.
- No breaking storage, wire, or JNI change is planned. A narrow validation binding may be added if existing parser access cannot meet the admission contract.

## Capabilities

### New Capabilities

- `geo-database-integrity`: correct geo matching, validated publication, and file-bound update status.

### Modified Capabilities

- None.

## Impact

- Rust ripdpi-geo and Kotlin core:service geo assets; core:engine only if parser admission needs a binding.
- No new production dependency, external service, locale change, or golden update.
- Six confirmed audit findings receive separate fix commits. Public format compatibility is limited to the tested legacy formats.
