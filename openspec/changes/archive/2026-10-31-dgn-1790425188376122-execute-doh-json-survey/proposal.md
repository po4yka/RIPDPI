# Change: Execute DoH JSON survey in monitor engine

Task ID: `DGN-1790425188376122`

## Why

The registered `doh_json_survey` stage reports a pending placeholder instead of measuring its selected DNS targets. A scan can therefore claim to include the survey without any resolver evidence.

## What Changes

- Kotlin selects the survey for scan DNS targets. The Rust stage queries each distinct domain through the public JSON resolver panel and records per-resolver results.
- The stage reports an aggregate outcome based on completed responses and stops new requests after cancellation or deadline.
- A scan without DNS targets sends no JSON DoH requests. No breaking wire change is planned.

## Capabilities

### New Capabilities

- `diagnostics/doh-json-survey`: Explicit, bounded measurement of user-requested DNS targets through vendor JSON DoH endpoints.

### Modified Capabilities

- None.

## Impact

- Kotlin diagnostics planning and local-network admission; Rust diagnostics contracts, HTTP client, probes, and monitor engine; diagnostic documentation.
- No new external service, dependency, JNI field, Kotlin resource, or persisted schema.
