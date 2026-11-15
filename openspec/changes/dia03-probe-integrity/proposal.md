# Change: Repair DIA-03 probe integrity

Task ID: `DGN-1794732416004558`

## Why

The DIA-03 audit reproduced false HTTP blockpage decisions, acceptance of malformed H3 responses, and an incorrect PMTU timeout reason. These errors can misstate network health or candidate quality.

## What Changes

- Reject malformed H3 response fields before a successful protocol verdict.
- Bound H3 frame buffering if the audit risk is confirmed.
- Request supported HTTP representations and reject unsupported coding without a clean verdict.
- Keep weak text fingerprints from confirming a blockpage without enough context.
- Separate PMTU local timeout from the scan deadline.
- No breaking schema or configuration change is planned.

## Capabilities

### New Capabilities

- `diagnostics/probe-integrity`: HTTP representation, fingerprint, and H3 validation requirements.

### Modified Capabilities

- `diagnostics/pmtu-interpretation`: preserve the source of time budget exhaustion.

## Impact

- Rust diagnostics HTTP and failure classifier crates, the local h3 source patch, and their tests.
- Existing evidence, RAW_PATH/Direct admission, trust, cancellation, and export contracts remain required.
- No new production dependency, remote service, or active external probe.
