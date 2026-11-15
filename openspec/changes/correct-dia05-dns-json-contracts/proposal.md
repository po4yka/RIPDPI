# Change: Correct DNS JSON survey evidence

Task ID: `DGN-1794732375813278`

## Why

DIA-05 audit found unrelated JSON answers accepted as successful, lost DNS response semantics, misclassified socket timeouts, unknown analytics marked healthy, and two library adapter errors. Correct these results without changing the DNS provider panel or binary DoH.

## What Changes

- Bind JSON answers to the requested name and type and reject malformed records.
- Preserve NODATA, NXDOMAIN, DNS server errors and transport timeouts.
- Preserve unknown connectivity analytics and conservative library verdicts.
- Build library GET query parameters without changing endpoint parameters.
- BREAKING internal Rust helper/status API changes require all repository callers and API snapshots to change together. No persisted schema, JNI, protobuf or configuration migration is required.

## Capabilities

### New Capabilities

None.

### Modified Capabilities

- `diagnostics/doh-json-survey`: validate vendor DNS JSON and preserve response, error and unknown evidence.

## Impact

- Rust diagnostics-contracts, diagnostics-http, diagnostics-probes and monitor-engine.
- Tests, internal Rust API snapshots and diagnostics architecture documentation.
- No new dependencies, provider changes, golden changes or external probes.
