# Change: Repair STD-05 profile and rotation contracts

Task ID: `TRN-1794198826397434`

## Why

The STD-05 audit found an unmeasured wire-size claim, stateful profile validation, a lost diagnostic first-flight plan and inconsistent default-alias validation. Tests and documentation also overstate browser parity and misstate the pinned backend limit.

## What Changes

- Report static profile checks without a claim about unmeasured wire size.
- Validate rotating profiles without selecting a connection profile or changing counters.
- Preserve a plan for the first ClientHello after a successful TLS probe.
- Accept the documented default alias at the consumer gate.
- Separate implementation snapshots from browser and external compatibility evidence.
- State the actual Rust API and hardware limits for TLS 1.3 cipher order.
- Keep one repair commit per finding. No stored schema or TLS wire policy change is planned.

## Capabilities

### New Capabilities

- `tls-profile-contracts`: profile metadata, pure selection validation and diagnostic first-flight contracts.

### Modified Capabilities

- None.

## Impact

- Native TLS profiles, xHTTP validation and diagnostic TLS capture; related tests and documentation.
- A test-only dependency on the existing workspace certificate generator may be needed.
- No production dependency, credential, external service, golden blessing or release artifact.
