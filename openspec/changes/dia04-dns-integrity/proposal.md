# Change: Repair DNS integrity and ECH evidence

Task ID: `DGN-1794732408586377`

## Why

The DIA-04 audit found false resolver agreement, hidden sinkhole addresses, lost EDE facts, incorrect response attribution, and disconnected or invalid CDN ECH cache data. These faults can mislead diagnostics or bypass the direct-socket protection contract.

## What Changes

- Repair all eight confirmed findings with one implementation commit per finding.
- Preserve query binding, bounded parsing, resolver errors and the distinction between observations and DNSSEC validation.
- Add local regression coverage and preserve explicit device and external compatibility limits.
- No breaking schema change or new dependency is planned.

## Capabilities

### New Capabilities

- `diagnostics/dns-integrity`: Correct agreement, evidence and CDN configuration behavior.

### Modified Capabilities

- None. Existing wire validation remains required.

## Impact

- Rust diagnostics contracts, runner, DNS, TLS, ECH DNS and existing platform socket connectors.
- No backend, protocol expansion, user secret, golden fixture or dependency change.
