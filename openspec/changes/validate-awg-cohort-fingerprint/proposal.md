# Change: Validate AWG cohort fingerprints during import

Task ID: `DAT-1791479983861691`

## Why

The importer carries cohort fingerprints but does not compare them with the AWG parameters. Inconsistent bundles can create profiles that cannot connect.

## What Changes

- Reject AWG entries whose present fingerprint differs from the resolved parameters.
- Keep valid siblings and return a fixed rejection reason.
- Accept bundles and INI files without fingerprint metadata.

## Capabilities

### New Capabilities

- `awg-cohort-integrity`: Validate optional server cohort metadata before import.

### Modified Capabilities

- None.

## Impact

- Kotlin runtime-state subscription parser and core data JVM tests. No schema, native, storage, dependency, or deployment changes. Malformed metadata is now rejected.
